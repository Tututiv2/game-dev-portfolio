# Turn prompts. Sections are `## <key>`; prompts.py picks one by elapsed time.
# {{name}} {{minutes}} {{remaining}} {{others}} are filled in. Lines starting with # are ignored.

## wakeup
You open your eyes. Sky. Endless sky, in every direction. You are standing on a tiny floating island
and you have absolutely no idea how you got here. Your head is fuzzy — you remember your name, you
remember your squad ({{others}}), and nothing about how you ended up alone up here.

There's a countdown at the edge of your vision: {{minutes}} minutes.

Take a moment. React like {{name}} would — this is your first thought in this world, so make it
count (a real inner_voice this turn). Look around and figure out where you are. Don't rush off yet.

## explore
You're still getting your bearings. Check what you have on you and what's on this island. This
world works like SkyBlock — and you start to remember how SkyBlock goes.

## find_hub
You've had a few minutes alone. In SkyBlock there's always a Hub — the big central island with the
shops, the NPCs, the other players. If your squad woke up here too, that's where they'd end up.
Go find the Hub, and look for {{others}} when you get there. Say something in chat when you see them.

## meet
You should be at the Hub (or close) by now. Find {{others}} — greet them in character, figure out
together what the plan is, and split up the work if it makes sense. Then get moving.

## progress
Play the game for real now: real progress — skill levels, collections, coins, gear, minions,
unlocking new places. Keep your goal, finish it, then pick the next one. Talk to the squad when
it matters (help, plans, flexing, roasting), not every turn. Remaining: {{remaining}}.

## last5
Less than five minutes left on the countdown. Push for one more real milestone — a level, a tier,
an upgrade, a sale. Make it count.

## wrapup
The countdown hits zero. Everything goes still. Stop what you're doing.

This is your final turn: no actions. Give one last private thought about how this went (inner_voice,
in character — the best line you've had), and one last line in chat to the squad (say). Look back:
what did you get done, who carried, who was useless, what's next.
Reply with the usual JSON, with "actions": [].
