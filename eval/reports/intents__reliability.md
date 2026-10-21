# On-device intent evaluation: reliability

- Dataset: eval/intents/intents.json v1 (55 items: 30 dev, 25 held-out)
- Chain: OnDeviceIntents.classify (the app's on-device order); clock Wed 21 Oct 2026 14:00 IST; no network.

| Split | Language | Intent correct | Args correct (items with labelled args) |
|---|---|---|---|
| dev | en | 9/10 | 3/3 |
| dev | hinglish | 20/20 | 11/11 |
| heldout | en | 9/9 | 4/4 |
| heldout | hinglish | 14/16 | 9/9 |
| dev | all | 29/30 | 14/14 |
| heldout | all | 23/25 | 13/13 |

## Misses
| Item | Split | Text | Expected | Got |
|---|---|---|---|---|
| d30 | dev | what's the weather today | none | local:get_weather {} |
| h05 | heldout | Yeh plan abhi rok do | plan.pause | none {} |
| h21 | heldout | Plan ko dobara shuru karo | plan.resume | none {} |
