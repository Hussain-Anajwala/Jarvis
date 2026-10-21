# Memory retrieval evaluation: baseline

- Cases: eval/memory/memory_cases.json v1 (14 synthetic memories, 20 queries: 10 dev, 10 held-out)

| Split | Best answer first (or asks when memories conflict) | Nothing retrieved when nothing is relevant | Unrelated memories retrieved | Secret retrieved |
|---|---|---|---|---|
| dev | 5/8 | 2/2 | 0 | 0 |
| heldout | 8/9 | 1/1 | 0 | 0 |

| Query | Split | Expected | Retrieved | Asked |
|---|---|---|---|---|
| q01 what's my favourite song | dev | m1 | m1 | false |
| q02 who is my mentor | dev | m2 | m2 | false |
| q03 when is Asha's birthday | dev | m3 | m3 | false |
| q04 which university do I go to | dev | m4 | m4 | false |
| q05 do I like tea or coffee | dev | m5 | m5 | false |
| q06 who is my sister | dev | m6, m7 | m7, m6 | false |
| q07 what is my wifi password | dev |  |  | false |
| q08 what's the weather like | dev |  |  | false |
| q09 what's my favourite food | dev | m13, m14 | m13, m14 | false |
| q10 meri behen kahan rehti hai | dev | m7, m6 |  | false |
| q11 who teaches compilers | heldout | m10 | m10 | false |
| q12 where is my gym | heldout | m8 | m8 | false |
| q13 when is my exam | heldout | m12 | m12 | false |
| q14 what's my name | heldout | m9 | m9 | false |
| q15 tell me about Zara | heldout | m6 | m6 | false |
| q16 what do I drink in the morning | heldout | m5 |  | false |
| q17 mera college kaunsa hai | heldout | m4 | m4 | false |
| q18 book a cab to the airport | heldout |  |  | false |
| q19 who is Prof Mehta | heldout | m10 | m10 | false |
| q20 what song do I love | heldout | m1 | m1 | false |
