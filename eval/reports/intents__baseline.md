# On-device intent evaluation: baseline

- Dataset: eval/intents/intents.json v1 (55 items: 30 dev, 25 held-out)
- Chain: OnDeviceIntents.classify (the app's on-device order); clock Wed 21 Oct 2026 14:00 IST; no network.

| Split | Language | Intent correct | Args correct (items with labelled args) |
|---|---|---|---|
| dev | en | 8/10 | 3/3 |
| dev | hinglish | 4/20 | 0/11 |
| heldout | en | 8/9 | 3/4 |
| heldout | hinglish | 2/16 | 0/9 |
| dev | all | 12/30 | 3/14 |
| heldout | all | 10/25 | 3/13 |

## Misses
| Item | Split | Text | Expected | Got |
|---|---|---|---|---|
| d01 | dev | Kal subah 9 baje mujhe project yaad dilana | reminder.create | none {} |
| d02 | dev | 30 minute ka timer laga do | timer.start | none {} |
| d03 | dev | Mera latest reminder cancel karo | reminder.cancel | local:calendar.cancel {} |
| d04 | dev | Kal wali reminder ka time kya tha? | reminder.list | local:reminder.create {} |
| d05 | dev | Is plan ko pause kar do | plan.pause | none {} |
| d06 | dev | Nahi, woh mat karna | approval.no | approval.unclear {} |
| d09 | dev | shaam 7 baje dawai lene ki yaad dilana | reminder.create | none {} |
| d10 | dev | 10 baje meeting ki yaad dila do | clarify | none {} |
| d11 | dev | mujhe yaad dilana | clarify | none {} |
| d12 | dev | plan resume karo | plan.resume | none {} |
| d13 | dev | mere reminders dikhao | reminder.list | local:reminder.create {} |
| d14 | dev | saare reminders cancel kar do | reminder.cancel | local:calendar.cancel {} |
| d15 | dev | 5 minute ka timer set karo | timer.start | none {} |
| d17 | dev | yes | approval.nothing_pending | none {} |
| d18 | dev | kal dopahar 2 baje doctor ke paas jaane ki yaad dilana | reminder.create | none {} |
| d19 | dev | parson subah 6 baje gym yaad dilana | reminder.create | none {} |
| d22 | dev | 1 ghante baad pani peene ki yaad dilana | reminder.create | none {} |
| d30 | dev | what's the weather today | none | local:get_weather {} |
| h01 | heldout | Parso shaam 5 baje bill bharne ki yaad dilana | reminder.create | none {} |
| h02 | heldout | 2 minute ka timer chalu karo | timer.start | none {} |
| h03 | heldout | Akhri reminder hata do | reminder.cancel | local:reminder.create {} |
| h04 | heldout | Mere saare reminders batao | reminder.list | local:reminder.create {} |
| h05 | heldout | Yeh plan abhi rok do | plan.pause | none {} |
| h07 | heldout | Nahi rehne do | approval.no | approval.unclear {} |
| h08 | heldout | Kal 8 baje uthne ki yaad dilana | clarify | none {} |
| h09 | heldout | Aaj raat 10 baje mummy ko call karne ki yaad dilana | reminder.create | none {} |
| h11 | heldout | Set a 45 minute timer | timer.start | none {} |
| h18 | heldout | haan | approval.nothing_pending | none {} |
| h19 | heldout | subah 6:30 baje yoga ki yaad dilana | reminder.create | none {} |
| h20 | heldout | 15 minute baad chai ki yaad dilana | reminder.create | none {} |
| h21 | heldout | Plan ko dobara shuru karo | plan.resume | none {} |
| h23 | heldout | Kal subah meeting yaad dilana | clarify | none {} |
| h25 | heldout | Saturday ko 11 baje dentist ki yaad dilana | clarify | none {} |
