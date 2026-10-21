# On-device intent evaluation v2: final

- Dataset: eval/intents/intents_v2.json v2 (61 items: 31 dev, 30 held-out)
- Chain: OnDeviceIntents.classify; clock Wed 21 Oct 2026 14:00 IST; no network.

| Split | Language | Intent correct | Args correct (items with labelled args) |
|---|---|---|---|
| dev | en | 13/13 | 11/11 |
| dev | hinglish | 14/14 | 9/9 |
| dev | hindi | 4/4 | 4/4 |
| dev | all | 31/31 | 24/24 |
| heldout | en | 14/14 | 11/11 |
| heldout | hinglish | 10/10 | 8/8 |
| heldout | hindi | 6/6 | 3/3 |
| heldout | all | 30/30 | 22/22 |

## Misses
| Item | Split | Text | Expected | Got |
|---|---|---|---|---|

