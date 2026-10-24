# SECOND WATCH — build prompt

A co-op Minecraft horror mod. Fabric, Minecraft 26.2, two players.

*A watch is the shift where one of you stays awake. You are also the second group to try.*

---

## 0. The thesis

Every AI-built Minecraft horror mod is single-player-shaped: a monster spawns, it hunts you, you
run. Add a second player and it is that, with a friend. Nobody has built one where the horror is
**made of** the second player.

**The gate:** if a mechanic is still scary alone, it does not ship. Every system here must be
*undefined* at one player — not weaker, undefined. `PhantomDirector.consider()` throws below two
live players, and there is a test asserting it, because with one observer nothing can ever be
established as false.

### The law

> **It can only say things you have already said.**

True in every run, no exceptions. It has a vocabulary and the vocabulary is yours. Which produces
the sentence the whole game hangs on:

> **Talking is how you stay together, and talking is what arms the thing that separates you.**

Every word either player speaks is filed. Quiet players are safe and lost; talkative players are
good company and easy to counterfeit.

### Why it is not PvP

Nobody is infected, nobody turns, nobody has to fight anybody. There is no traitor role to accuse,
because a role can be voted on and a fight is not fear. The threat is a third thing that wears the
people you trust, and the only weapon against it is knowing your friend well enough to notice the
seams.

---

## 1. The plot

**Night one is an ordinary Minecraft night.** You dig in, you're fine, dawn comes. Nothing happens.
This is the control sample and it is the most important ten minutes in the mod.

**Night two, dawn doesn't come.** The clock runs past 6am and keeps going. That is the only thing
wrong. No monster, no message, no title card.

**Then you find that someone was here first.** A dirt hut. A broken pickaxe. An arrow in a tree at
head height. Then their base — lived in, ransacked, torches burned to nothing — and **their
journal**, which is this run's tutorial and this run's trap, because it was written by people who
were guessing and `falseLeads` of its pages are confidently wrong.

**The split.** You're walking together, talking. It takes one of you: a phantom of your friend walks
ahead of you, and the same thing happens to him, in the other direction. It speaks in his real
recorded voice. Neither of you knows for up to two minutes. The scare is not the separation — it is
the ordinary conversation that continues afterwards, and the moment somebody says *"wait, say that
again. where are you?"*

**You catch it.** Somebody notices a repeat, or the volume that never fades, or that it has never
once spoken first. This should feel like a win, and it is — briefly.

**It learns.** Your passphrase gets used against you (§4).

**The ending.** You find the last survivor's camp and what they built to get out. It works. **It
fits one.** The final scene of the run is two people deciding out loud, over voice chat, which of
them leaves — while something that can imitate both of them listens.

---

## 2. Architecture — three layers, and layer 3 never touches the world

The mistake that kills mods like this: faking things by actually changing blocks per-player, then
fighting rubber-banding forever.

1. **TRUTH** — server, authoritative, vanilla. Collision, damage, inventory, block state. One world.
2. **LIE** — server. Owns `Dread`, `RunRules`, `VoiceCorpus` and decides what each client is *told*.
   Emits a per-player fabrication manifest.
3. **RENDER** — client. Draws the manifest as pure visuals and audio. Never writes world state.

You can walk **through** a phantom and through anything it appears to stand behind. That is correct,
and it is the best scare in the mod the first time somebody walks through a door their friend can
see.

**Rule: if a fabrication can kill you, it is in the wrong layer.** The lie can make you kill
yourself, which is worse anyway.

---

## 3. The per-run roll — where replayability lives

You cannot re-scare somebody with a scare they've seen. So what varies is the **rule set**, and
working out which rules you got is the gameplay. Everything below is rolled from the world seed and
is deterministic — same seed, same run, so a session that went wrong on camera can be replayed.

| Rolls | Range | Code |
|---|---|---|
| **Tell** — the giveaway | 6 options | `Tell` |
| **Trigger** — what draws it | 6 options | `Trigger` |
| Nights before it stops pretending | 4–6 | `RunRules.nights` |
| Size of the previous group | 3–6 | `RunRules.groupSize` |
| Journal entries that are wrong | 1–2 | `RunRules.falseLeads` |

### The tells (all detectable without speech-to-text)

`FIXED_VOLUME` never gets quieter with distance · `MONO` arrives in both ears · `NO_BREATH` no room
tone in the gaps · `STALE_ONLY` can't use anything from the last 10 minutes · `ONLY_REPLIES` never
speaks first · `REPEATS_ITSELF` will reuse a line verbatim.

### The triggers

`TALKING` · `LIGHT` · `DARKNESS` · `STILLNESS` · `SEPARATION` · `DIGGING`

Every trigger is something the players **cannot simply stop doing** — it is either how you survive
the night, how you make progress, or how you stay together. A trigger you can abstain from is a
puzzle with a solution, and a solved horror game is a walking simulator.

---

## 4. The passphrase, and its fuse

Once players learn the law, the counter is obvious: agree a challenge phrase, and make each other
say it.

It works. And it has a fuse: **the moment you say it out loud it is recorded**, and
`VoiceCorpus.ARMING_DELAY_MS` (90s) later it belongs to the thing as much as to you. Your
countermeasure is destroyed by the act of using it.

Late in a run, two people are screaming improvised nonsense at each other to prove they are human.
That is both genuinely frightening and the funniest possible footage, which is the tonal mix these
videos need.

On a `STALE_ONLY` run the journal's advice is real: anything said in the last ten minutes is safe.
That is the reward for reading the book and believing the right page.

---

## 5. The journal

Generated from **this run's actual rules**, signed by **this run's actual dead**, and wrong in
`falseLeads` places. Enforced by tests:

- the **law** page is always present and never a false lead — it is the players' floor
- the true tell/trigger pages describe the real rolled values
- the false pages never accidentally state the truth
- **nobody signs an entry dated after the night they were lost**
- **no entry names somebody who was never in the group** (three hardcoded names were caught by this)
- nothing in the rendered text distinguishes a true page from a false one

And one page that is always true and never explained:

```
[night 4 - gormless]
it used a name today. one of ours, in one of our voices, but the NAME is not ours.

tutut

we do not have a tutut. we have never had a tutut. {other} says it is practising
```

---

## 6. Escalation — `Dread`

Never decreases. Not on reaching safety, not on sleeping, not on playing well. Doing well buys
**time**, never ground; a game that sells relief has taught the player the situation is negotiable.
Night number also sets a floor, so a competent pair still reaches the ending — just later and in
better shape. That is what makes any given night's recording watchable.

| Tier | What it may do |
|---|---|
| `DENIABLE` | misplaced objects, a sound with no source. Reads as Minecraft jank |
| `ARGUABLE` | the two clients disagree about geometry |
| `SOCIAL` | the phantom gets a body and a voice — the split becomes possible |
| `AUTHORED` | it stops improvising and starts steering |
| `HUNT` | it stops pretending |

The phantom gets no body until `SOCIAL`, by which point the players have spent two tiers learning
that this world produces small wrong things. They have been trained to recognise its handwriting.
That is why the split lands instead of reading as a gimmick.

---

## 7. The phantom is boring

`PhantomDirector` enforces it. It walks, it waits at doors, it mines, and it says things your friend
genuinely said. A phantom that behaves strangely is caught in four seconds; a phantom that behaves
normally is followed for two minutes. Also enforced:

- it never wears the person looking at it
- it never targets a player who can currently see their real partner
- a lure always **increases** the distance to the partner (tested)
- 45s minimum between fabrications — scarcity is most of what sells it

---

## 8. Scope — everything is in

Custom dimension, custom mobs and models, a boss, crafting. The endless night runs through one
vanilla packet (`ClientboundSetTimePacket`, sent **per player**), so the sky can disagree between
clients without touching the server's real day counter.

Cheap mobs that outperform custom ones: **followers** (walk to 20 blocks, stop, never attack, and
there are more of them every night) and **the walk** (a mob that moves with player cadence — from 40
blocks in the dark you will call out to it).

---

## 8b. THE QUIET — the boss, and it is not the Understudy

The thing that has been wearing your friend is **not** the worst thing in the world. It is the
smaller predator, and it has spent five nights walking you toward the bigger one while carefully
never going there itself.

|  | Understudy | Quiet |
|---|---|---|
| made of | stolen sound | the absence of it |
| finds you by | listening | already knowing |
| speed | walks like a person | slower, and it does not stop |
| you beat it by | noticing | making it audible |

### The inversion (this is the whole reason to have a different boss)

A boss that simply ignores voice would waste five nights of learned skill — that is the
bait-and-switch trap. This one makes the skill **misfire** instead.

Inside the Quiet's field there is no ambience, no mobs, no footsteps, no music, and **no voice
chat**. Players have spent the entire run learning that a voice cutting out means *the Understudy is
doing something — stay calm, find the seam.* That trained instinct is precisely the wrong response
at precisely the wrong moment.

The field eats voice chat deliberately. A boss that politely lets you coordinate is not the climax
of a game whose whole mechanic was talking. The fight is co-op with the co-op switched off, so
whatever plan you have must be agreed **before** you walk in — which is what all that passphrase
culture was training.

### Foreshadowed by absence, from night one

`PhantomDirector` refuses to produce a phantom for any player inside `Quiet.avoidRadius()`, and
clamps every lure to stop at that line. So for five nights the Understudy is everywhere except one
region, and the journal page saying *"it never comes down here"* is **TRUE** — not a false lead.
That is why the last group went down there.

### Herding, measured

Lures bend toward the Quiet by `HERD_BIAS = 0.55`, but the away-from-partner invariant always
outranks it. Controlled A/B against a director that does not know where the Quiet is (same seed,
same victim, same line — only the knowledge differs):

- **6.85 blocks** of bend per 24-block lure — a 29% deflection
- over ~30 lures a night, **206 blocks of drift** toward it
- invisible in any single lure, a landslide across a run

It genuinely fails for one arrangement, and that is correct: when both players are on the same side
of the Quiet, the one further out can only be walked away from their partner by also being walked
away from the boss. The Understudy gets a bias, not a steering wheel.

### The fight

You cannot damage absence, so you do not try — you force it to be **heard**. A crafted **resonator**
drives sound into the field; the Quiet cannot survive being perceived. This is what crafting is for.

The cost is the design: **the resonator is the loudest thing in the game**, and loud is how
everything here finds you. On a `TALKING` or `DIGGING` run it is a dinner bell. So the fight is not
"hit the boss", it is *"decide when you can afford to make the worst noise in the world"*, twice,
next to somebody you currently cannot speak to.

Tuned and asserted: **2.0 firings** to end it, `ACTIVE_MS` 6s, `COOLDOWN_MS` 45s, `RANGE` 20. Two
decisions is a fight; one is a cutscene and six is homework, and there is a test holding that shape
if anybody retunes the constants.

Every phase makes it **bigger and louder**, never weaker — silence radius 18 → 34. The pocket that
was safe in phase 1 has quietly stopped being safe by phase 3, and the mod never mentions it.

---

## 8c. The night, wired

Before this the systems existed and none of them touched: mobs never spawned on their own, and the
rolled `Trigger` was flavour text. Now a night escalates because of what the players do.

**Triggers feed dread.** `TriggerModel` charges a player for tripping this run's trigger - talking,
standing lit, standing dark, standing still, being apart, digging. Sampled once a second. A whole
chapter of relentless carelessness is worth about **half a tier**: enough that the journal's advice
is worth obeying, never enough to skip an act. Obeying it earns nothing - dread does not go down,
and this must not become a back door.

**The bestiary arrives on tier**, capped, one at a time, never in view and never closer than 28
blocks. The approach is the product: a Follower that spawns at forty blocks and takes two minutes to
become undeniable beats ten that spawn at ten.

| Tier | What the world may contain |
|---|---|
| `DENIABLE` | **nothing.** Not one spawn |
| `ARGUABLE` | one Follower |
| `SOCIAL` | Followers, Watchers, the first Mimic, Swarm |
| `AUTHORED` | **the Crawler** - the first thing that can kill you |
| `HUNT` | the Tall One |

### The schedule is stated, not derived

Night one is always `DENIABLE` and the second-to-last night is always `AUTHORED`.

That is not tidiness. The floor used to be a linear ramp, and on a four-night run it stepped
straight from `SOCIAL` to `HUNT` - so `AUTHORED` never happened, so the Crawler never spawned, so
light was never worth carrying. **An entire mechanic was missing and nothing failed.** Found by
running a real server and reading the tiers as they went past; there is now a test over 400 seeds
asserting every run reaches `SOCIAL`, `AUTHORED` and `HUNT`.

Runs are 4-6 nights for the same reason: three cannot fit an ordinary opening, a night that teaches
the law, a night with teeth, and an ending.

---

## 8d. The ending - it fits one

`The Seat` is a craftable block (3 iron blocks, 2 amethyst blocks, a resonator - expensive on
purpose, so building it is a decision rather than something you stumble into while tidying a chest).

Three rules, all of them there to make the players talk to each other:

- **It does nothing before the final night.** Finding it on night two only teaches them the exit
  exists and that they have to survive to reach it. That makes a much better night three.
- **Leaving takes twenty seconds**, announced to both players, counted down out loud with a tone a
  second. Nobody leaves on an impulse, and whoever is staying has to stand there through all of it.
- **It fits one.**

Whoever takes it goes to spectator - out of the run, still watching, unable to help. That is the
point of the ending and it should be uncomfortable.

An interrupted departure (death, disconnect) does **not** spend the seat. A run made unwinnable by
bad luck is a run the players stop taking seriously.

The rules live in `core/TheSeat.java`, pure and tested, so no special case in the block code can
quietly soften them. The final scene of a run is two people deciding out loud which of them leaves,
while something that can imitate both of their voices listens to that conversation.

---

## 9. Test strategy

`com.secondwatch.core` has **zero Minecraft imports**, so the whole design is testable in ~12
seconds instead of by playing for two hours. 72 tests, and they have already earned it:

- **an integer overflow that disabled the entire mod.** `lastEventMs` started at `Long.MIN_VALUE`,
  so `nowMs - lastEventMs` overflowed negative, the cooldown read as permanently active and the
  phantom could never fire. In game this presents as *"the mod doesn't do anything"* — close to
  undiagnosable live.
- a diarist who died on night 2 and kept writing until night 5
- three hardcoded names in templates crediting people who were never in the group

Non-negotiable invariants, all covered:

- the phantom's audio always traces back to a real recorded utterance
- it never speaks in the wrong person's voice
- `consider()` throws below two live players — *the thesis as a unit test*
- dread never decreases; a negative feed throws rather than clamping
- same seed → identical rules, group and journal text

---

## 10. Toolchain

See `API_NOTES.md`. The headline: **Minecraft 26.x ships deobfuscated**, so there is no `mappings`
line in `build.gradle` and that is correct. Java 25, Loom 1.17.19, Gradle 9.5.1, Fabric API
0.157.0+26.2, voicechat-api **2.6.20** (the API artifact is versioned separately from the mod).

---

## 11. Open

- What the objectives actually are that force you outside — the survival skeleton underneath.
- What it is, in fiction. Current instinct: never explained, and the previous group's best guess is
  one of the false leads.
- Whether a rescue exists at all, or the seat is the only exit.
