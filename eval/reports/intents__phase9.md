# On-device intent evaluation: phase9

- Dataset: eval/intents/intents.json v1 (55 items: 30 dev, 25 held-out)
- Chain: OnDeviceIntents.classify (the app's on-device order); clock Wed 21 Oct 2026 14:00 IST; no network.

| Split | Language | Intent correct | Args correct (items with labelled args) |
|---|---|---|---|
| dev | en | 9/10 | 3/3 |
| dev | hinglish | 20/20 | 11/11 |
| heldout | en | 8/9 | 3/4 |
| heldout | hinglish | 13/16 | 7/9 |
| dev | all | 29/30 | 14/14 |
| heldout | all | 21/25 | 10/13 |

## Misses
| Item | Split | Text | Expected | Got |
|---|---|---|---|---|
| d30 | dev | what's the weather today | none | local:get_weather {} |
| h01 | heldout | Parso shaam 5 baje bill bharne ki yaad dilana | reminder.create | reminder.create {title=Parso bill bharne, hour=17, minute=0, day=today} |
| h03 | heldout | Akhri reminder hata do | reminder.cancel | reminder.cancel {latest=false} |
| h04 | heldout | Mere saare reminders batao | reminder.list | local:reminder.create {} |
| h05 | heldout | Yeh plan abhi rok do | plan.pause | none {} |
| h11 | heldout | Set a 45 minute timer | timer.start | none {} |
| h21 | heldout | Plan ko dobara shuru karo | plan.resume | none {} |
