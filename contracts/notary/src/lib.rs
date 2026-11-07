//! The CostGuard notary contract.
//!
//! The notary service (apps/python/notary) calls `anchor` with the Merkle root of a batch of journal receipts.
//! Anyone can then read the anchor and check a receipt against it with `verify`, without trusting CostGuard.
//!
//! The tree is the one of `ai.costguard.core.journal.internal.Merkle`: leaves are receipt hashes (hex), a node
//! is sha256(left || right) over the raw 32-byte values, a level with an odd count carries its last node up.
//! A proof step is `R:<hex>` when the sibling sits on the right and `L:<hex>` when it sits on the left.
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

#[near]
#[derive(BorshStorageKey)]
enum Key {
    Anchors,
    Notaries,
}

#[near(contract_state)]
#[derive(PanicOnDefault)]
pub struct Notary {
    owner: AccountId,
    notaries: IterableSet<AccountId>,
    anchors: LookupMap<String, Anchor>,
    count: u64,
}

fn is_hash(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn decode(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn pair(left: &str, right: &str) -> String {
    let mut both = decode(left);
    both.extend(decode(right));
    env::sha256(&both).iter().map(|b| format!("{b:02x}")).collect()
}

#[near]
impl Notary {
    #[init]
    pub fn new(owner: AccountId) -> Self {
        let mut notaries = IterableSet::new(Key::Notaries);
        notaries.insert(owner.clone());
        Self { owner, notaries, anchors: LookupMap::new(Key::Anchors), count: 0 }
    }

    fn require_owner(&self) {
        require!(env::predecessor_account_id() == self.owner, "Only the owner can do this");
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
    pub fn anchor(&mut self, batch_id: String, merkle_root: String, receipt_count: u32, period_start_ms: u64,
                  period_end_ms: u64) -> bool {
        require!(self.notaries.contains(&env::predecessor_account_id()), "Only a notary can anchor");
        require!(!batch_id.is_empty() && batch_id.len() <= 64, "Bad batch id");
        require!(is_hash(&merkle_root), "The root must be 64 lowercase hex digits");
        require!(receipt_count > 0, "A batch holds at least one receipt");
        require!(period_start_ms <= period_end_ms, "The period ends before it starts");
        if let Some(existing) = self.anchors.get(&batch_id) {
            require!(existing.merkle_root == merkle_root, "This batch is anchored with another root");
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
        let Some(anchor) = self.anchors.get(&batch_id) else { return false };
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
        level.chunks(2).map(|c| if c.len() == 2 { pair(&c[0], &c[1]) } else { c[0].clone() }).collect()
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
                out.push(format!("{}{}", if i % 2 == 0 { "R:" } else { "L:" }, level[sibling]));
            }
            level = up(&level);
            i /= 2;
        }
        out
    }

    fn as_account(name: &str) {
        testing_env!(VMContextBuilder::new().predecessor_account_id(name.parse().unwrap()).block_timestamp(
            1_000_000_000).build());
    }

    fn contract() -> Notary {
        as_account("owner.testnet");
        Notary::new("owner.testnet".parse().unwrap())
    }

    #[test]
    fn root_matches_the_core_and_the_notary_service() {
        // pinned in MerkleTest of the core and test_notary.py of the notary service
        let leaves: Vec<String> = (0..5).map(leaf).collect();
        assert_eq!(root(&leaves), "fda95846c507b71d6a40de553d7855aec27b3ada0afa61c177732c7f90afa3e9");
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
}
