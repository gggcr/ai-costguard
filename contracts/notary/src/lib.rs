//! The CostGuard notary contract.
//!
//! The notary service (apps/python/notary) calls `anchor` with the Merkle root of a batch of journal receipts.
//! Anyone can then read the anchor and check a receipt against it with `verify`, without trusting CostGuard.
//!
//! The tree is the one of `ai.costguard.core.journal.internal.Merkle`: leaves are receipt hashes (hex), a node
//! is sha256(left || right) over the raw 32-byte values, a level with an odd count carries its last node up.
//! A proof step is `R:<hex>` when the sibling sits on the right and `L:<hex>` when it sits on the left.
//!
//! The notary also registers the hash of every published mandate version (`register_mandate`) and confirms large
//! operations a person approved (`confirm_operation`). Both store hashes only: what was hashed stays in the core,
//! which can show it to whoever checks. The canonical forms are in docs/adr/0009-notary-mandates-confirmations.md.
//!
//! No tenant id is stored: a root reveals nothing about the receipts, and the tenant stays off the public chain.

use near_sdk::store::{IterableSet, LookupMap};
use near_sdk::{env, log, near, require, AccountId, BorshStorageKey, PanicOnDefault};

#[near(serializers = [json, borsh])]
#[derive(Clone, Debug, PartialEq)]
pub struct Anchor {
    pub merkle_root: String,
    pub receipt_count: u32,
    pub period_start_ms: u64,
    pub period_end_ms: u64,
    pub anchored_at_ms: u64,
    pub block_height: u64,
}

/// One published version of a mandate: the hash of the terms the gateway enforces.
#[near(serializers = [json, borsh])]
#[derive(Clone, Debug, PartialEq)]
pub struct MandateRecord {
    pub mandate_hash: String,
    pub registered_at_ms: u64,
    pub block_height: u64,
}

/// A large operation a person approved. The approver is a salted hash, never a name.
#[near(serializers = [json, borsh])]
#[derive(Clone, Debug, PartialEq)]
pub struct Confirmation {
    pub approver_hash: String,
    pub confirmed_at_ms: u64,
    pub block_height: u64,
}

/// New keys go at the end: the index of a key is its storage prefix.
#[near]
#[derive(BorshStorageKey)]
enum Key {
    Anchors,
    Notaries,
    Mandates,
    MandateVersions,
    Confirmations,
}

#[near(contract_state)]
#[derive(PanicOnDefault)]
pub struct Notary {
    owner: AccountId,
    notaries: IterableSet<AccountId>,
    anchors: LookupMap<String, Anchor>,
    count: u64,
    mandates: LookupMap<String, MandateRecord>,
    mandate_versions: LookupMap<String, u32>,
    confirmations: LookupMap<String, Confirmation>,
    confirmation_count: u64,
}

fn mandate_key(mandate_id: &str, version: u32) -> String {
    format!("{mandate_id}#{version}")
}

fn is_hash(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn decode(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

fn pair(left: &str, right: &str) -> String {
    let mut both = decode(left);
    both.extend(decode(right));
    env::sha256(&both)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

#[near]
impl Notary {
    #[init]
    pub fn new(owner: AccountId) -> Self {
        let mut notaries = IterableSet::new(Key::Notaries);
        notaries.insert(owner.clone());
        Self {
            owner,
            notaries,
            anchors: LookupMap::new(Key::Anchors),
            count: 0,
            mandates: LookupMap::new(Key::Mandates),
            mandate_versions: LookupMap::new(Key::MandateVersions),
            confirmations: LookupMap::new(Key::Confirmations),
            confirmation_count: 0,
        }
    }

    fn require_notary(&self, what: &str) {
        require!(
            self.notaries.contains(&env::predecessor_account_id()),
            format!("Only a notary can {what}")
        );
    }

    fn require_owner(&self) {
        require!(
            env::predecessor_account_id() == self.owner,
            "Only the owner can do this"
        );
    }

    pub fn add_notary(&mut self, account_id: AccountId) {
        self.require_owner();
        self.notaries.insert(account_id);
    }

    pub fn remove_notary(&mut self, account_id: AccountId) {
        self.require_owner();
        require!(account_id != self.owner, "The owner stays a notary");
        self.notaries.remove(&account_id);
    }

    pub fn notaries(&self) -> Vec<AccountId> {
        self.notaries.iter().cloned().collect()
    }

    /// Stores the root of a batch. The same batch with the same root again is a no-op that returns false, so the
    /// notary can repeat a call after a crash; the same batch with another root is refused.
    pub fn anchor(
        &mut self,
        batch_id: String,
        merkle_root: String,
        receipt_count: u32,
        period_start_ms: u64,
        period_end_ms: u64,
    ) -> bool {
        self.require_notary("anchor");
        require!(!batch_id.is_empty() && batch_id.len() <= 64, "Bad batch id");
        require!(
            is_hash(&merkle_root),
            "The root must be 64 lowercase hex digits"
        );
        require!(receipt_count > 0, "A batch holds at least one receipt");
        require!(
            period_start_ms <= period_end_ms,
            "The period ends before it starts"
        );
        if let Some(existing) = self.anchors.get(&batch_id) {
            require!(
                existing.merkle_root == merkle_root,
                "This batch is anchored with another root"
            );
            return false;
        }
        let anchor = Anchor {
            merkle_root: merkle_root.clone(),
            receipt_count,
            period_start_ms,
            period_end_ms,
            anchored_at_ms: env::block_timestamp_ms(),
            block_height: env::block_height(),
        };
        self.anchors.insert(batch_id.clone(), anchor);
        self.count += 1;
        log!(
            r#"EVENT_JSON:{{"standard":"costguard-notary","version":"1.0.0","event":"anchor","data":[{{"batch_id":"{}","merkle_root":"{}","receipt_count":{}}}]}}"#,
            batch_id,
            merkle_root,
            receipt_count
        );
        true
    }

    pub fn get_anchor(&self, batch_id: String) -> Option<Anchor> {
        self.anchors.get(&batch_id).cloned()
    }

    pub fn anchor_count(&self) -> u64 {
        self.count
    }

    /// True when the receipt hash, with this proof, leads to the root anchored for the batch.
    pub fn verify(&self, batch_id: String, leaf: String, proof: Vec<String>) -> bool {
        let Some(anchor) = self.anchors.get(&batch_id) else {
            return false;
        };
        if !is_hash(&leaf) {
            return false;
        }
        let mut node = leaf;
        for step in proof {
            let (side, sibling) = step.split_at(2.min(step.len()));
            if !is_hash(sibling) {
                return false;
            }
            node = match side {
                "R:" => pair(&node, sibling),
                "L:" => pair(sibling, &node),
                _ => return false,
            };
        }
        node == anchor.merkle_root
    }

    /// Upgrades a contract deployed before mandates and confirmations: same owner, notaries and anchors, nothing
    /// registered or confirmed yet. Only the contract account can call it, in the same transaction as the deploy.
    #[private]
    #[init(ignore_state)]
    pub fn migrate() -> Self {
        let old: NotaryV1 =
            env::state_read().unwrap_or_else(|| env::panic_str("No state to migrate"));
        Self {
            owner: old.owner,
            notaries: old.notaries,
            anchors: old.anchors,
            count: old.count,
            mandates: LookupMap::new(Key::Mandates),
            mandate_versions: LookupMap::new(Key::MandateVersions),
            confirmations: LookupMap::new(Key::Confirmations),
            confirmation_count: 0,
        }
    }

    /// Stores the hash of a published mandate version. The same version with the same hash again is a no-op that
    /// returns false; the same version with another hash is refused, so a registered version never changes.
    /// Versions may arrive in any order: the latest is the highest one registered.
    pub fn register_mandate(
        &mut self,
        mandate_id: String,
        version: u32,
        mandate_hash: String,
    ) -> bool {
        self.require_notary("register a mandate");
        require!(
            !mandate_id.is_empty() && mandate_id.len() <= 64 && !mandate_id.contains('#'),
            "Bad mandate id"
        );
        require!(version > 0, "Mandate versions start at 1");
        require!(
            is_hash(&mandate_hash),
            "The mandate hash must be 64 lowercase hex digits"
        );
        let key = mandate_key(&mandate_id, version);
        if let Some(existing) = self.mandates.get(&key) {
            require!(
                existing.mandate_hash == mandate_hash,
                "This mandate version is registered with another hash"
            );
            return false;
        }
        self.mandates.insert(
            key,
            MandateRecord {
                mandate_hash: mandate_hash.clone(),
                registered_at_ms: env::block_timestamp_ms(),
                block_height: env::block_height(),
            },
        );
        if self
            .mandate_versions
            .get(&mandate_id)
            .is_none_or(|latest| *latest < version)
        {
            self.mandate_versions.insert(mandate_id.clone(), version);
        }
        log!(
            r#"EVENT_JSON:{{"standard":"costguard-notary","version":"1.1.0","event":"mandate","data":[{{"mandate_id":"{}","version":{},"mandate_hash":"{}"}}]}}"#,
            mandate_id,
            version,
            mandate_hash
        );
        true
    }

    /// The record of a mandate version; without a version, of the latest one registered.
    pub fn get_mandate(&self, mandate_id: String, version: Option<u32>) -> Option<MandateRecord> {
        let version = version.or_else(|| self.mandate_versions.get(&mandate_id).copied())?;
        self.mandates
            .get(&mandate_key(&mandate_id, version))
            .cloned()
    }

    /// The latest version registered for the mandate.
    pub fn mandate_version(&self, mandate_id: String) -> Option<u32> {
        self.mandate_versions.get(&mandate_id).copied()
    }

    /// True when exactly this hash is registered for this version of the mandate.
    pub fn verify_mandate(&self, mandate_id: String, version: u32, mandate_hash: String) -> bool {
        self.mandates
            .get(&mandate_key(&mandate_id, version))
            .is_some_and(|m| m.mandate_hash == mandate_hash)
    }

    /// Records that a person approved the operation with this hash. The same approver again is a no-op that returns
    /// false; another approver for the same operation is refused, so a confirmation never changes.
    pub fn confirm_operation(&mut self, operation_hash: String, approver_hash: String) -> bool {
        self.require_notary("confirm an operation");
        require!(
            is_hash(&operation_hash),
            "The operation hash must be 64 lowercase hex digits"
        );
        require!(
            is_hash(&approver_hash),
            "The approver hash must be 64 lowercase hex digits"
        );
        if let Some(existing) = self.confirmations.get(&operation_hash) {
            require!(
                existing.approver_hash == approver_hash,
                "This operation is confirmed by another approver"
            );
            return false;
        }
        self.confirmations.insert(
            operation_hash.clone(),
            Confirmation {
                approver_hash: approver_hash.clone(),
                confirmed_at_ms: env::block_timestamp_ms(),
                block_height: env::block_height(),
            },
        );
        self.confirmation_count += 1;
        log!(
            r#"EVENT_JSON:{{"standard":"costguard-notary","version":"1.1.0","event":"confirmation","data":[{{"operation_hash":"{}","approver_hash":"{}"}}]}}"#,
            operation_hash,
            approver_hash
        );
        true
    }

    pub fn get_confirmation(&self, operation_hash: String) -> Option<Confirmation> {
        self.confirmations.get(&operation_hash).cloned()
    }

    /// True when the operation with this hash is confirmed; given an approver hash, only if by that approver.
    pub fn is_confirmed(&self, operation_hash: String, approver_hash: Option<String>) -> bool {
        self.confirmations
            .get(&operation_hash)
            .is_some_and(|c| approver_hash.is_none_or(|a| a == c.approver_hash))
    }

    pub fn confirmation_count(&self) -> u64 {
        self.confirmation_count
    }
}

/// The state layout before mandates and confirmations, read once by `migrate`.
#[near]
struct NotaryV1 {
    owner: AccountId,
    notaries: IterableSet<AccountId>,
    anchors: LookupMap<String, Anchor>,
    count: u64,
}

#[cfg(test)]
mod tests {
    use super::*;
    use near_sdk::test_utils::VMContextBuilder;
    use near_sdk::testing_env;
    use sha2::{Digest, Sha256};

    fn leaf(i: usize) -> String {
        hex::encode(Sha256::digest(format!("{}\nleaf {i}", "0".repeat(64))))
    }

    fn up(level: &[String]) -> Vec<String> {
        level
            .chunks(2)
            .map(|c| {
                if c.len() == 2 {
                    pair(&c[0], &c[1])
                } else {
                    c[0].clone()
                }
            })
            .collect()
    }

    fn root(leaves: &[String]) -> String {
        let mut level = leaves.to_vec();
        while level.len() > 1 {
            level = up(&level);
        }
        level[0].clone()
    }

    fn proof(leaves: &[String], index: usize) -> Vec<String> {
        let (mut level, mut i, mut out) = (leaves.to_vec(), index, vec![]);
        while level.len() > 1 {
            let sibling = if i % 2 == 0 { i + 1 } else { i - 1 };
            if sibling < level.len() {
                out.push(format!(
                    "{}{}",
                    if i % 2 == 0 { "R:" } else { "L:" },
                    level[sibling]
                ));
            }
            level = up(&level);
            i /= 2;
        }
        out
    }

    fn as_account(name: &str) {
        testing_env!(VMContextBuilder::new()
            .predecessor_account_id(name.parse().unwrap())
            .block_timestamp(1_000_000_000)
            .build());
    }

    fn contract() -> Notary {
        as_account("owner.testnet");
        Notary::new("owner.testnet".parse().unwrap())
    }

    #[test]
    fn root_matches_the_core_and_the_notary_service() {
        // pinned in MerkleTest of the core and test_notary.py of the notary service
        let leaves: Vec<String> = (0..5).map(leaf).collect();
        assert_eq!(
            root(&leaves),
            "fda95846c507b71d6a40de553d7855aec27b3ada0afa61c177732c7f90afa3e9"
        );
    }

    #[test]
    fn anchors_and_verifies_every_receipt() {
        let mut c = contract();
        let leaves: Vec<String> = (0..5).map(leaf).collect();
        let r = root(&leaves);
        assert!(c.anchor("b1".into(), r.clone(), 5, 10, 20));
        assert_eq!(c.get_anchor("b1".into()).unwrap().anchored_at_ms, 1000);
        for i in 0..5 {
            assert!(c.verify("b1".into(), leaves[i].clone(), proof(&leaves, i)));
        }
        assert!(!c.verify("b1".into(), leaf(9), proof(&leaves, 0)));
        assert!(!c.verify("b2".into(), leaves[0].clone(), proof(&leaves, 0)));
        assert!(!c.verify("b1".into(), leaves[0].clone(), vec!["X:00".into()]));
    }

    #[test]
    fn repeating_a_batch_is_a_no_op() {
        let mut c = contract();
        assert!(c.anchor("b1".into(), leaf(0), 1, 1, 1));
        assert!(!c.anchor("b1".into(), leaf(0), 1, 1, 1));
        assert_eq!(c.anchor_count(), 1);
    }

    #[test]
    #[should_panic(expected = "another root")]
    fn a_batch_cannot_change_its_root() {
        let mut c = contract();
        c.anchor("b1".into(), leaf(0), 1, 1, 1);
        c.anchor("b1".into(), leaf(1), 1, 1, 1);
    }

    #[test]
    #[should_panic(expected = "Only a notary")]
    fn strangers_cannot_anchor() {
        let mut c = contract();
        as_account("stranger.testnet");
        c.anchor("b1".into(), leaf(0), 1, 1, 1);
    }

    #[test]
    fn the_owner_adds_notaries() {
        let mut c = contract();
        c.add_notary("service.testnet".parse().unwrap());
        as_account("service.testnet");
        assert!(c.anchor("b1".into(), leaf(0), 1, 1, 1));
        assert_eq!(c.notaries().len(), 2);
    }

    #[test]
    #[should_panic(expected = "64 lowercase hex")]
    fn a_bad_root_is_refused() {
        let mut c = contract();
        c.anchor("b1".into(), "ABC".into(), 1, 1, 1);
    }

    const MANDATE: &str = "0b9d2c7e-4a51-4c1e-8f0a-3d2b1c4e5f60";

    #[test]
    fn registers_mandate_versions_and_reads_them_back() {
        let mut c = contract();
        assert!(c.register_mandate(MANDATE.into(), 1, leaf(1)));
        assert!(c.register_mandate(MANDATE.into(), 2, leaf(2)));
        assert_eq!(c.mandate_version(MANDATE.into()), Some(2));
        assert_eq!(
            c.get_mandate(MANDATE.into(), None).unwrap().mandate_hash,
            leaf(2)
        );
        let first = c.get_mandate(MANDATE.into(), Some(1)).unwrap();
        assert_eq!(first.mandate_hash, leaf(1));
        assert_eq!(first.registered_at_ms, 1000);
        assert!(c.verify_mandate(MANDATE.into(), 1, leaf(1)));
        assert!(!c.verify_mandate(MANDATE.into(), 1, leaf(2)));
        assert!(!c.verify_mandate(MANDATE.into(), 3, leaf(1)));
        assert_eq!(c.get_mandate("unknown".into(), None), None);
        assert_eq!(c.mandate_version("unknown".into()), None);
    }

    #[test]
    fn an_older_version_arriving_late_keeps_the_latest() {
        let mut c = contract();
        assert!(c.register_mandate(MANDATE.into(), 3, leaf(3)));
        assert!(c.register_mandate(MANDATE.into(), 2, leaf(2)));
        assert_eq!(c.mandate_version(MANDATE.into()), Some(3));
    }

    #[test]
    fn repeating_a_mandate_version_is_a_no_op() {
        let mut c = contract();
        assert!(c.register_mandate(MANDATE.into(), 1, leaf(1)));
        assert!(!c.register_mandate(MANDATE.into(), 1, leaf(1)));
    }

    #[test]
    #[should_panic(expected = "registered with another hash")]
    fn a_mandate_version_cannot_change_its_hash() {
        let mut c = contract();
        c.register_mandate(MANDATE.into(), 1, leaf(1));
        c.register_mandate(MANDATE.into(), 1, leaf(2));
    }

    #[test]
    #[should_panic(expected = "Only a notary can register a mandate")]
    fn strangers_cannot_register_mandates() {
        let mut c = contract();
        as_account("stranger.testnet");
        c.register_mandate(MANDATE.into(), 1, leaf(1));
    }

    #[test]
    #[should_panic(expected = "versions start at 1")]
    fn version_zero_is_refused() {
        let mut c = contract();
        c.register_mandate(MANDATE.into(), 0, leaf(1));
    }

    #[test]
    #[should_panic(expected = "mandate hash must be 64 lowercase hex")]
    fn a_bad_mandate_hash_is_refused() {
        let mut c = contract();
        c.register_mandate(MANDATE.into(), 1, "ABC".into());
    }

    #[test]
    fn confirms_an_operation_by_its_hash() {
        let mut c = contract();
        assert!(!c.is_confirmed(leaf(7), None));
        assert!(c.confirm_operation(leaf(7), leaf(8)));
        assert!(c.is_confirmed(leaf(7), None));
        assert!(c.is_confirmed(leaf(7), Some(leaf(8))));
        assert!(!c.is_confirmed(leaf(7), Some(leaf(9))));
        let confirmation = c.get_confirmation(leaf(7)).unwrap();
        assert_eq!(confirmation.approver_hash, leaf(8));
        assert_eq!(confirmation.confirmed_at_ms, 1000);
        assert_eq!(c.confirmation_count(), 1);
        assert_eq!(c.get_confirmation(leaf(6)), None);
    }

    #[test]
    fn repeating_a_confirmation_is_a_no_op() {
        let mut c = contract();
        assert!(c.confirm_operation(leaf(7), leaf(8)));
        assert!(!c.confirm_operation(leaf(7), leaf(8)));
        assert_eq!(c.confirmation_count(), 1);
    }

    #[test]
    #[should_panic(expected = "confirmed by another approver")]
    fn a_confirmation_cannot_change_its_approver() {
        let mut c = contract();
        c.confirm_operation(leaf(7), leaf(8));
        c.confirm_operation(leaf(7), leaf(9));
    }

    #[test]
    #[should_panic(expected = "Only a notary can confirm an operation")]
    fn strangers_cannot_confirm() {
        let mut c = contract();
        as_account("stranger.testnet");
        c.confirm_operation(leaf(7), leaf(8));
    }

    #[test]
    #[should_panic(expected = "approver hash must be 64 lowercase hex")]
    fn a_bad_approver_hash_is_refused() {
        let mut c = contract();
        c.confirm_operation(leaf(7), "someone@example.com".into());
    }

    #[test]
    fn migrate_keeps_the_anchors_of_the_first_version() {
        as_account("owner.testnet");
        let mut notaries = IterableSet::new(Key::Notaries);
        notaries.insert("owner.testnet".parse::<AccountId>().unwrap());
        notaries.insert("service.testnet".parse::<AccountId>().unwrap());
        let mut anchors = LookupMap::new(Key::Anchors);
        let anchor = Anchor {
            merkle_root: leaf(0),
            receipt_count: 1,
            period_start_ms: 1,
            period_end_ms: 1,
            anchored_at_ms: 5,
            block_height: 9,
        };
        anchors.insert("b1".to_string(), anchor.clone());
        let mut old = NotaryV1 {
            owner: "owner.testnet".parse().unwrap(),
            notaries,
            anchors,
            count: 1,
        };
        old.notaries.flush();
        old.anchors.flush();
        env::state_write(&old);

        let mut c = Notary::migrate();
        assert_eq!(c.get_anchor("b1".into()), Some(anchor));
        assert_eq!(c.anchor_count(), 1);
        assert_eq!(c.notaries().len(), 2);
        as_account("service.testnet");
        assert!(c.register_mandate(MANDATE.into(), 1, leaf(1)));
        assert!(c.confirm_operation(leaf(7), leaf(8)));
    }
}
