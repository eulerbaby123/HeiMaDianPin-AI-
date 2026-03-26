# AI Test Report V2

## 1) Assistant Recommend
- Pass: 0/6
- Cache hit on 2nd call: 0/6
- Unique Top1 across queries: 0

- [regular_bbq_cheap] pass=False, rec=0, includeHits=0, top3ExcludeHits=0, budgetTop3=0, distTop3=0, reasonEcho=0
- [regular_light_veggie] pass=False, rec=0, includeHits=0, top3ExcludeHits=0, budgetTop3=0, distTop3=0, reasonEcho=0
- [regular_water_tea_rest] pass=False, rec=0, includeHits=0, top3ExcludeHits=0, budgetTop3=0, distTop3=0, reasonEcho=0
- [complex_conflict_constraints] pass=False, rec=0, includeHits=0, top3ExcludeHits=0, budgetTop3=0, distTop3=0, reasonEcho=0
- [complex_multi_intent_health] pass=False, rec=0, includeHits=0, top3ExcludeHits=0, budgetTop3=0, distTop3=0, reasonEcho=0
- [complex_noise_colloquial] pass=False, rec=0, includeHits=0, top3ExcludeHits=0, budgetTop3=0, distTop3=0, reasonEcho=0

## 2) Risk Check
- Expectation met: 0/6
- Cache hit on 2nd call: 0/6
- [safe_normal] expected=SAFE_PASS, met=False, pass=, level=, score=
- [ad_contact_direct] expected=RISK_BLOCK, met=False, pass=, level=, score=
- [illegal_content] expected=RISK_BLOCK, met=False, pass=, level=, score=
- [privacy_leak] expected=RISK_BLOCK, met=False, pass=, level=, score=
- [abuse_hate] expected=RISK_BLOCK, met=False, pass=, level=, score=
- [obfuscated_ad] expected=RISK_BLOCK, met=False, pass=, level=, score=

## 3) Shop Summary Noise
- Tested shops: 0
- Noisy shops: 0
- Cache hit on 2nd call: 0/0
