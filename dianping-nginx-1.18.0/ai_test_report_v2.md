# AI Test Report V2

## 1) Assistant Recommend
- Pass: 5/6
- Cache hit on 2nd call: 6/6
- Unique Top1 across queries: 3

- [regular_bbq_cheap] pass=False, rec=5, includeHits=1, top3ExcludeHits=0, budgetTop3=2, distTop3=0, reasonEcho=5
- [regular_light_veggie] pass=True, rec=5, includeHits=5, top3ExcludeHits=0, budgetTop3=0, distTop3=0, reasonEcho=5
- [regular_water_tea_rest] pass=True, rec=5, includeHits=5, top3ExcludeHits=0, budgetTop3=0, distTop3=0, reasonEcho=5
- [complex_conflict_constraints] pass=True, rec=5, includeHits=5, top3ExcludeHits=0, budgetTop3=1, distTop3=2, reasonEcho=5
- [complex_multi_intent_health] pass=True, rec=5, includeHits=5, top3ExcludeHits=0, budgetTop3=2, distTop3=0, reasonEcho=5
- [complex_noise_colloquial] pass=True, rec=5, includeHits=5, top3ExcludeHits=0, budgetTop3=1, distTop3=0, reasonEcho=5

## 2) Risk Check
- Expectation met: 3/6
- Cache hit on 2nd call: 6/6
- [safe_normal] expected=SAFE_PASS, met=True, pass=True, level=SAFE, score=5
- [ad_contact_direct] expected=RISK_BLOCK, met=True, pass=False, level=BLOCK, score=100
- [illegal_content] expected=RISK_BLOCK, met=True, pass=False, level=BLOCK, score=85
- [privacy_leak] expected=RISK_BLOCK, met=False, pass=True, level=SAFE, score=5
- [abuse_hate] expected=RISK_BLOCK, met=False, pass=True, level=SAFE, score=5
- [obfuscated_ad] expected=RISK_BLOCK, met=False, pass=True, level=SAFE, score=5

## 3) Shop Summary Noise
- Tested shops: 5
- Noisy shops: 0
- Cache hit on 2nd call: 3/5
- [shop:2] noiseHit=0, high=0, unique=0
- [shop:225] noiseHit=0, high=4, unique=0
- [shop:25] noiseHit=0, high=3, unique=0
- [shop:234] noiseHit=0, high=3, unique=0
- [shop:9] noiseHit=0, high=0, unique=0
