# On-device intent evaluation v2: baseline

- Dataset: eval/intents/intents_v2.json v2 (61 items: 31 dev, 30 held-out)
- Chain: OnDeviceIntents.classify; clock Wed 21 Oct 2026 14:00 IST; no network.

| Split | Language | Intent correct | Args correct (items with labelled args) |
|---|---|---|---|
| dev | en | 7/13 | 4/11 |
| dev | hinglish | 8/14 | 3/9 |
| dev | hindi | 0/4 | 0/4 |
| dev | all | 15/31 | 7/24 |
| heldout | en | 7/14 | 4/11 |
| heldout | hinglish | 9/10 | 4/8 |
| heldout | hindi | 0/6 | 0/3 |
| heldout | all | 16/30 | 8/22 |

## Misses
| Item | Split | Text | Expected | Got |
|---|---|---|---|---|
| v2d02 | dev | Create a reminder for 8:30 PM | clarify {"missing":"title"} | local:reminder.create {} |
| v2d03 | dev | Create a reminder for 8:30 PM and label it DevOps project | reminder.create {"hour":20,"title_contains":"devops project","day":"today","minute":30} | local:reminder.create {} |
| v2d05 | dev | आज शाम 8:30 बजे मुझे प्रोजेक्ट याद दिलाना। | reminder.create {"hour":20,"title_contains":"project|प्रोजेक्ट","day":"today","minute":30} | none {} |
| v2d09 | dev | Parso shaam 5 baje mujhe yaad dilana | clarify {"missing":"title"} | reminder.create {title=Parso, hour=17, minute=0, day=today} |
| v2d10 | dev | कल सुबह नौ बजे मुझे जिम याद दिलाना | reminder.create {"hour":9,"title_contains":"gym|जिम","day":"tomorrow","minute":0} | none {} |
| v2d11 | dev | List all my reminders | reminder.list {"scope":"all"} | reminder.list {} |
| v2d12 | dev | Show today's reminders | reminder.list {"scope":"today"} | reminder.list {} |
| v2d13 | dev | Mere saare reminders batao | reminder.list {"scope":"all"} | local:reminder.create {} |
| v2d14 | dev | Mere aaj jo bhi reminder hai, batao | reminder.list {"scope":"today"} | local:reminder.create {} |
| v2d15 | dev | आज के सारे रिमाइंडर बताओ। | reminder.list {"scope":"today"} | none {} |
| v2d16 | dev | Akhri reminder hata do | reminder.cancel {"latest":true} | reminder.cancel {latest=false} |
| v2d18 | dev | अख़िरी रिमाइंडर हटाओ | reminder.cancel {"latest":true} | none {} |
| v2d19 | dev | Set a 45 minute timer | timer.start {"seconds":2700} | none {} |
| v2d20 | dev | Cancel the timer | timer.cancel {} | approval.nothing_pending {} |
| v2d21 | dev | Rok do | stop.contextual {} | none {} |
| v2d22 | dev | Dobara shuru karo | resume.contextual {} | none {} |
| v2d25 | dev | Nahi, 8 PM kar do | approval.correct {"hour":20} | approval.no {} |
| v2d26 | dev | No, make it 8 PM | approval.correct {"hour":20} | approval.no {} |
| v2d29 | dev | Kal ka reminder kab hai? | reminder.list {"scope":"tomorrow"} | reminder.list {} |
| v2d30 | dev | Forget that my gym is in Dadar | memory.forget {} | local:forget_memory {} |
| v2d31 | dev | Remind me on the 31st of November to file taxes | clarify {"missing":"day"} | clarify {missing=time} |
| v2h01 | heldout | Set a reminder at 9:15 PM to take my medicine | reminder.create {"hour":21,"title_contains":"medicine","day":"today","minute":15} | local:reminder.create {} |
| v2h02 | heldout | Make a reminder for tomorrow at 6 AM | clarify {"missing":"title"} | local:reminder.create {} |
| v2h03 | heldout | Create a reminder called submit assignment for 10 PM | reminder.create {"hour":22,"title_contains":"submit assignment","day":"today","minute":0} | local:reminder.create {} |
| v2h04 | heldout | What reminders do I have today? | reminder.list {"scope":"today"} | reminder.list {} |
| v2h05 | heldout | Show me all my upcoming reminders | reminder.list {"scope":"all"} | reminder.list {} |
| v2h07 | heldout | Start a 20 minute timer | timer.start {"seconds":1200} | none {} |
| v2h08 | heldout | Stop the timer | timer.cancel {} | approval.nothing_pending {} |
| v2h09 | heldout | Mujhe parso subah 7 baje gym yaad dilana | reminder.create {"hour":7,"title_contains":"gym","day":"day_after","minute":0} | reminder.create {title=parso gym, hour=7, minute=0, day=tomorrow} |
| v2h11 | heldout | Aaj ke reminders dikhao | reminder.list {"scope":"today"} | reminder.list {} |
| v2h12 | heldout | Saare reminders dikha do | reminder.list {"scope":"all"} | reminder.list {} |
| v2h17 | heldout | परसों शाम पाँच बजे मुझे प्रोजेक्ट याद दिलाना | reminder.create {"hour":17,"title_contains":"project|प्रोजेक्ट","day":"day_after","minute":0} | none {} |
| v2h18 | heldout | कल शाम सात बजे मुझे याद दिलाना | clarify {"missing":"title"} | none {} |
| v2h19 | heldout | मेरे सारे रिमाइंडर दिखाओ | reminder.list {"scope":"all"} | none {} |
| v2h20 | heldout | टाइमर बंद करो | timer.cancel {} | none {} |
| v2h21 | heldout | हाँ | approval.yes {} | approval.unclear {} |
| v2h22 | heldout | नहीं | approval.no {} | approval.unclear {} |
| v2h25 | heldout | Ruk jao | stop.contextual {} | none {} |
| v2h26 | heldout | Resume it | resume.contextual {} | none {} |
| v2h27 | heldout | Remind me on Funday at 5 pm to rest | clarify {"missing":"day"} | reminder.create {title=Funday to rest, hour=17, minute=0, day=today} |
| v2h30 | heldout | Mera aakhri reminder cancel kar do | reminder.cancel {"latest":true} | reminder.cancel {latest=false} |
