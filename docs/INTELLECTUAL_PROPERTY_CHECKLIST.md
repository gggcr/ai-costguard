# Intellectual property checklist

Facts from the repository on 2026-11-23, for counsel and for a buyer's due diligence. Nothing here is a legal
opinion, and nothing here states that ownership is clean: **it is not documented yet.**

## Facts

| Fact | Evidence |
|---|---|
| Two Git authors | `git shortlog -sn --all --no-merges`: `melanxolia801` 13 commits (2026-11-11 → 11-14, ~107,000 added lines: the bulk of the backend, Python services, web client, ADRs, runbooks); `gggcr` 34 commits (2026-11-12 → 11-23, ~15,800 added lines: the Western-market work, billing, AWS, security fixes, documentation). Line counts include generated and data files. |
| Code written with an AI assistant | the README discloses that most code was written with AI coding assistants under the team's direction; the commits of 2026-11-22/23 were produced with Claude Code. Counsel: copyright in AI-assisted code depends on human authorship and the jurisdiction; the assistant's provider terms assign output to the user. |
| License history | `be4b509` (2026-11-14, by `melanxolia801`): MIT license, "AI CostGuard contributors", added for the NEAR hackathon submission. `0785dc6` (2026-11-23, by `gggcr`): replaced by "All Rights Reserved". |
| The repository is public | `git ls-remote https://github.com/gggcr/ai-costguard.git` works without credentials (checked 2026-11-23). Every version up to `0785dc6` was publicly available under MIT. |
| Hackathon | The README's English section was written for NEAR hackathon judges. The hackathon's terms are not in the repository. |
| Contributor agreements | none in the repository (no CLA, no assignment) |
| Third-party code | dependencies only, inventoried in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md); no copied third-party source files were found by this review (not exhaustive) |

## What this means (to be confirmed by counsel)

1. **Copies made under MIT stay MIT.** The change to "All Rights Reserved" applies to new versions only. Anyone who
   obtained the public repository before 2026-11-23 23:05 (+03:00) may keep using, modifying and redistributing that
   version under MIT. A buyer cannot get exclusivity over that code; only over later work, the brand, customers and
   know-how.
2. **The change of license needs every copyright holder's consent.** "AI CostGuard contributors" includes
   `melanxolia801`. Relicensing their contributions as proprietary requires their written agreement (or an
   assignment to the seller).
3. **A sale needs a chain of title:** a written assignment (or exclusive license) of copyright from each author to
   the selling entity, signed. Until then the repository must not be described as owned by one party.
4. **Hackathon terms** may grant the organiser rights or require the submission to stay open: obtain and read them.

## Checklist before a sale

- [ ] Identify the selling legal entity (not yet chosen).
- [ ] Written copyright assignment from `melanxolia801` to the seller, covering all contributions (commits
      `4aa2691`…`be4b509` and any later ones). **Missing.**
- [ ] Written copyright assignment from `gggcr` (if the seller is a company). **Missing.**
- [ ] Consent of all authors to the relicensing in `0785dc6`. **Missing.**
- [ ] NEAR hackathon terms obtained and reviewed. **Missing.**
- [ ] Decide whether to make the repository private now (it does not undo past MIT copies).
- [ ] Trademark search for "CostGuard" / "AI CostGuard" in the US and EU; no registration exists.
- [ ] Domain names for the product: none registered by this project.
- [ ] Confirm the license conclusions in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
- [ ] Representations in the sale agreement limited to what the documents above support.
