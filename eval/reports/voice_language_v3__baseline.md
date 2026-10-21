# Voice and language evaluation v3: baseline

- Dataset: eval/intents/voice_language_v3.json (56 items). Clock Wed 21 Oct 2026 14:00 IST; no network.
- reply items are judged on the rendered reply by script/marker words, independent of the app's language detection.

| Part | Split | en | hinglish | hindi | all |
|---|---|---|---|---|---|
| meeting | dev | 0/6 | 0/1 | 0/1 | 0/8 |
| meeting | heldout | 0/4 | 0/1 | 0/1 | 0/6 |
| answer | dev | 4/6 | 3/4 | 2/2 | 9/12 |
| answer | heldout | 2/4 | 3/3 | 1/1 | 6/8 |
| reply | dev | 3/3 | 0/8 | 0/3 | 3/14 |
| reply | heldout | 2/2 | 0/2 | 0/4 | 2/8 |

## Misses
| Item | Split | Lang | Got |
|---|---|---|---|
| m01 | dev | en | {"hour":10,"days":1,"location":"college","title":"Meeting tomorrow at","minute":0} |
| m02 | dev | en | {"hour":10,"days":1,"location":"VJTI","title":"DevOps project evaluation","minute":0} |
| m03 | dev | en | {"hour":15,"days":2,"location":"the library","title":"Meeting about the DevOps project evaluation on Friday at","minute":0} |
| m04 | dev | en | {"hour":11,"days":1,"location":"Lilavati Hospital","title":"Doctor","minute":30} |
| m05 | dev | hinglish | {"hour":null,"days":1,"location":null,"title":"Kal subah 10 baje VJTI mein project review","minute":null} |
| m06 | dev | hindi | {"hour":null,"days":1,"location":null,"title":null,"minute":null} |
| m07 | dev | en | {"hour":16,"days":1,"location":"college","title":"Meeting with Prof. Rao about the viva tomorrow at","minute":0} |
| m08 | dev | en | {"hour":18,"days":1,"location":"Dadar","title":"Meeting at","minute":0} |
| m09 | heldout | en | {"hour":9,"days":5,"location":"the office","title":"Client demo","minute":30} |
| m10 | heldout | en | {"hour":17,"days":1,"location":"Bandra","title":"Dentist","minute":0} |
| m11 | heldout | hinglish | {"hour":null,"days":1,"location":null,"title":"Parso shaam 6 baje Andheri mein team","minute":null} |
| m12 | heldout | hindi | {"hour":null,"days":1,"location":null,"title":null,"minute":null} |
| m13 | heldout | en | {"hour":14,"days":1,"location":"Powai to discuss the budget","title":"Meeting tomorrow at","minute":0} |
| m14 | heldout | en | {"hour":10,"days":1,"location":null,"title":"Meeting tomorrow at","minute":0} |
| a04 | dev | hinglish | new_request |
| a09 | dev | en | new_request |
| a10 | dev | en | new_request |
| a15 | heldout | en | retry |
| a18 | heldout | en | new_request |
| r02 | dev | hinglish | en: You have 1 reminder: gym at 7:00 PM tomorrow. |
| r03 | dev | hindi | en: You have 1 reminder: gym at 7:00 PM tomorrow. |
| r04 | dev | hinglish | en: I'll remind you to gym at 7:00 PM tomorrow. |
| r05 | dev | hindi | en: I'll remind you to gym at 7:00 PM tomorrow. |
| r07 | dev | hinglish | en: What should I remind you about? |
| r08 | dev | hinglish | en: Cancelled the reminder to gym. |
| r09 | dev | hinglish | en: Started your 10-minute timer. It ends at 2:10 PM. Say "cancel the timer" or tap Cancel to stop it. |
| r10 | dev | hindi | en: Your 10-minute timer has been cancelled. It won't ring. |
| r11 | dev | hinglish | en: Delete this memory: "my gym is in Dadar"? Say yes to delete it, or no to keep it. |
| r12 | dev | hinglish | en: Nothing is waiting for a yes or no right now. |
| r14 | dev | hinglish | en: Add "Project review" on Thu 22 Oct at 10:00 AM at VJTI to your calendar, with a reminder before it? Say yes to add it, or no. |
| r15 | heldout | hindi | en: You have 1 reminder: gym at 7:00 PM tomorrow. |
| r16 | heldout | hinglish | en: I'll remind you to gym at 7:00 PM tomorrow. |
| r18 | heldout | hinglish | en: Your 10-minute timer has been cancelled. It won't ring. |
| r19 | heldout | hindi | en: Cancelled the reminder to gym. |
| r20 | heldout | hindi | en: Nothing is waiting for a yes or no right now. |
| r22 | heldout | hindi | en: Add "Project review" on Thu 22 Oct at 10:00 AM at VJTI to your calendar, with a reminder before it? Say yes to add it, or no. |
