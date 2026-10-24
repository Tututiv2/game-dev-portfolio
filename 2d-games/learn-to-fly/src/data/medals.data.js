// ALL 48 MEDALS, VERBATIM (§7.6). The names and conditions are VERIFIED; the BP values are
// CALIBRATE — sized so the total is ~7,400, which is what makes Bounty Hunter 2 (earn 5,000
// bonus points) reachable without every other medal being mandatory.
//
// §7.5: medals are ACCOUNT-LEVEL. They stay unlocked through all played games and pay bonus
// points; challenges are per-playthrough and pay cash. That boundary lives in save.js and it
// is the one thing here that is genuinely painful to retrofit (landmine 12).
//
// Each medal owns its own predicate over the account. Check 16 runs a scripted scenario per
// medal and asserts it unlocks — which is the only way to discover that medal 40 is
// impossible because the wall's collision box is three feet too tall.

const ft = (n) => (a) => a.lifetime.distFt >= n;

export const MEDALS = [
  { id: 'story_mode', n: 1, name: 'Story Mode', condition: 'Complete Story Mode', bp: 500,
    test: (a) => !!a.flags.storyComplete },
  { id: 'classic_mode', n: 2, name: 'Classic Mode', condition: 'Complete Classic Mode', bp: 400,
    test: (a) => !!a.flags.classicComplete },
  { id: 'arcade_king', n: 3, name: 'Arcade King', condition: '12,500 total points across all arcade modes', bp: 500,
    test: (a) => a.lifetime.arcadePoints >= 12500 },

  { id: 'pacifist_1', n: 4, name: 'Pacifist 1', condition: '10 consecutive days without damaging anything', bp: 120,
    test: (a) => a.lifetime.pacifistDays >= 10 },
  { id: 'pacifist_2', n: 5, name: 'Pacifist 2', condition: '25 consecutive days without damaging anything', bp: 260,
    test: (a) => a.lifetime.pacifistDays >= 25 },

  { id: 'skull_crusher', n: 6, name: 'Skull Crusher', condition: 'Crash into the ground with great force', bp: 90,
    test: (a) => !!a.flags.skullCrush },
  { id: 'planet_crusher', n: 7, name: 'Planet Crusher', condition: 'Crash with extreme force', bp: 250,
    test: (a) => !!a.flags.planetCrush },

  { id: 'reverse_1', n: 8, name: 'Reverse 1', condition: 'Fly backwards 250 total feet', bp: 60,
    test: (a) => a.lifetime.reverseFt >= 250 },
  { id: 'reverse_2', n: 9, name: 'Reverse 2', condition: 'Fly backwards 15,000 total feet', bp: 300,
    test: (a) => a.lifetime.reverseFt >= 15000 },

  { id: 'diving_1', n: 10, name: 'Diving 1', condition: 'Submerge 100 total feet', bp: 60,
    test: (a) => a.lifetime.diveFt >= 100 },
  { id: 'diving_2', n: 11, name: 'Diving 2', condition: 'Submerge 1,000 total feet', bp: 160,
    test: (a) => a.lifetime.diveFt >= 1000 },

  { id: 'bounty_1', n: 12, name: 'Bounty Hunter 1', condition: 'Earn 1,000 bonus points total', bp: 100,
    test: (a) => a.lifetime.bpEarned >= 1000 },
  { id: 'bounty_2', n: 13, name: 'Bounty Hunter 2', condition: 'Earn 5,000 bonus points total', bp: 300,
    test: (a) => a.lifetime.bpEarned >= 5000 },

  // §7.7 — twenty minutes each, and exactly the kind of detail that makes a remake read as
  // loving rather than mechanical.
  { id: 'give_an_inch', n: 14, name: 'Give an inch...', condition: 'Click your money in the shop', bp: 25,
    test: (a) => (a.flags.moneyClicks || 0) >= 1 },
  { id: 'take_a_yard', n: 15, name: "He'll take a yard", condition: 'Click your money in the shop again', bp: 25,
    test: (a) => (a.flags.moneyClicks || 0) >= 2 },

  { id: 'the_brick', n: 16, name: 'The Brick', condition: 'Buy the secret glider from the catalogue', bp: 150,
    test: (a) => !!a.flags.boughtBrick },
  { id: 'revenge_brick', n: 17, name: 'Revenge of the Brick', condition: 'Destroy an obstacle using the Brick', bp: 300,
    test: (a) => !!a.flags.brickKill },
  { id: 'its_rob', n: 18, name: "It's Rob!", condition: 'Buy R.O.B. 64, then get resupplied mid-flight', bp: 220,
    test: (a) => !!a.flags.robRefuel },
  { id: 'rise', n: 19, name: 'Rise!', condition: 'Buy the phoenix feather, then get launched back up', bp: 220,
    test: (a) => !!a.flags.phoenixRise },

  { id: 'easy_way', n: 20, name: 'The Easy Way', condition: "Click the penguin's beak on the title screen", bp: 25,
    test: (a) => !!a.flags.beakClick },
  { id: 'achievement', n: 21, name: 'Achievement!', condition: 'Click the achievement icon', bp: 25,
    test: (a) => !!a.flags.medalIconClick },

  { id: 'high_flyer_1', n: 22, name: 'High Flyer 1', condition: 'Reach 250 ft altitude', bp: 80,
    test: (a) => a.lifetime.maxAltFt >= 250 },
  { id: 'high_flyer_2', n: 23, name: 'High Flyer 2', condition: 'Reach 1,000 ft altitude', bp: 260,
    test: (a) => a.lifetime.maxAltFt >= 1000 },

  { id: 'speedster_1', n: 24, name: 'Speedster 1', condition: 'Reach 100 mph', bp: 60,
    test: (a) => a.lifetime.maxSpeedMph >= 100 },
  { id: 'speedster_2', n: 25, name: 'Speedster 2', condition: 'Reach 500 mph', bp: 320,
    test: (a) => a.lifetime.maxSpeedMph >= 500 },

  { id: 'master_bouncer', n: 26, name: 'Master Bouncer', condition: 'Bounce 100 total times on water', bp: 180,
    test: (a) => a.lifetime.bounces >= 100 },
  { id: 'combo', n: 27, name: 'C-C-C-Combo', condition: 'Destroy all 5 obstacles in one run', bp: 450,
    test: (a) => !!a.flags.comboRun },

  { id: 'achiever_1', n: 28, name: 'Achiever 1', condition: 'Complete all challenges in one Story Mode game', bp: 400,
    test: (a) => !!a.flags.allChallengesStory },
  { id: 'achiever_2', n: 29, name: 'Achiever 2', condition: 'Complete all challenges in one Classic Mode game', bp: 400,
    test: (a) => !!a.flags.allChallengesClassic },

  { id: 'rich_bird_1', n: 30, name: 'Rich Bird 1', condition: 'Accumulate $25,000', bp: 100,
    test: (a) => a.lifetime.cashEarned >= 25000 },
  { id: 'rich_bird_2', n: 31, name: 'Rich Bird 2', condition: 'Accumulate $100,000', bp: 260,
    test: (a) => a.lifetime.cashEarned >= 100000 },

  { id: 'duration_1', n: 32, name: 'Duration 1', condition: 'Fly 60 total seconds', bp: 40,
    test: (a) => a.lifetime.airTimeS >= 60 },
  { id: 'duration_2', n: 33, name: 'Duration 2', condition: 'Fly 5 total minutes', bp: 80,
    test: (a) => a.lifetime.airTimeS >= 300 },
  { id: 'duration_3', n: 34, name: 'Duration 3', condition: 'Fly 25 total minutes', bp: 180,
    test: (a) => a.lifetime.airTimeS >= 1500 },
  { id: 'duration_4', n: 35, name: 'Duration 4', condition: 'Fly 1 total hour', bp: 320,
    test: (a) => a.lifetime.airTimeS >= 3600 },

  { id: 'destruction_1', n: 36, name: 'Destruction 1', condition: 'Destroy 1 obstacle', bp: 40,
    test: (a) => a.lifetime.destroyed >= 1 },
  { id: 'destruction_2', n: 37, name: 'Destruction 2', condition: 'Destroy each obstacle type at least once', bp: 200,
    test: (a) => Object.keys(a.lifetime.destroyedKinds || {}).length >= 5 },
  { id: 'destruction_3', n: 38, name: 'Destruction 3', condition: 'Destroy 25 total obstacles', bp: 160,
    test: (a) => a.lifetime.destroyed >= 25 },
  { id: 'destruction_4', n: 39, name: 'Destruction 4', condition: 'Destroy 75 total obstacles', bp: 340,
    test: (a) => a.lifetime.destroyed >= 75 },

  // VERIFIED at 600 ft, which is both a hard number for the wall's height and a promise that
  // going OVER it must be possible. Check 16 flies a maxed rig over it to keep that promise.
  { id: 'icbp', n: 40, name: 'I.C.B.P.', condition: 'Fly over the 600-foot wall', bp: 500,
    test: (a) => !!a.flags.overTheWall },

  { id: 'milestone_1', n: 41, name: 'Milestone 1', condition: 'Play 10 in-game days', bp: 40,
    test: (a) => a.lifetime.days >= 10 },
  { id: 'milestone_2', n: 42, name: 'Milestone 2', condition: 'Play 25 in-game days', bp: 80,
    test: (a) => a.lifetime.days >= 25 },
  { id: 'milestone_3', n: 43, name: 'Milestone 3', condition: 'Play 75 in-game days', bp: 180,
    test: (a) => a.lifetime.days >= 75 },
  // The clearest possible statement that THERE IS NO DAY LIMIT (§1). Days are your score,
  // not a resource you run out of, and this medal rewards taking 150 of them.
  { id: 'milestone_4', n: 44, name: 'Milestone 4', condition: 'Play 150 in-game days', bp: 340,
    test: (a) => a.lifetime.days >= 150 },

  { id: 'traveler_1', n: 45, name: 'Traveler 1', condition: 'Fly 500 total feet', bp: 30, test: ft(500) },
  { id: 'traveler_2', n: 46, name: 'Traveler 2', condition: 'Fly 5,000 total feet', bp: 60, test: ft(5000) },
  { id: 'traveler_3', n: 47, name: 'Traveler 3', condition: 'Fly 50,000 total feet', bp: 160, test: ft(50000) },
  { id: 'traveler_4', n: 48, name: 'Traveler 4', condition: 'Fly 150,000 total feet', bp: 340, test: ft(150000) },
];

export const MEDAL_BY_ID = Object.fromEntries(MEDALS.map((m) => [m.id, m]));
export const TOTAL_BP = MEDALS.reduce((s, m) => s + m.bp, 0);
