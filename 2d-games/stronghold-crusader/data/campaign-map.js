window.CAMPAIGN_MAP_DATA = {
  version: 1,
  world: {
    width: 2200,
    height: 1300,
    name: "Levant Frontier"
  },
  campaigns: [
    {
      id: "crusader-trail",
      name: "Crusader Trail",
      startNodeId: "m1"
    }
  ],
  nodes: [
    {
      id: "m1",
      campaignId: "crusader-trail",
      order: 1,
      title: "Arrival at Nicaea",
      objective: "Establish a foothold and feed your people.",
      enemy: "The Rat",
      biome: "Dry Basin",
      difficulty: "Easy",
      x: 220,
      y: 920,
      next: ["m2"],
      requires: []
    },
    {
      id: "m2",
      campaignId: "crusader-trail",
      order: 2,
      title: "Dust Road South",
      objective: "Secure wood lines while repelling light raids.",
      enemy: "The Snake",
      biome: "Dust Ridge",
      difficulty: "Easy",
      x: 460,
      y: 800,
      next: ["m3"],
      requires: ["m1"]
    },
    {
      id: "m3",
      campaignId: "crusader-trail",
      order: 3,
      title: "Antioch Counter-Siege",
      objective: "Break a siege and hold the gatehouse.",
      enemy: "The Pig",
      biome: "Rock Fortress",
      difficulty: "Normal",
      x: 700,
      y: 690,
      next: ["m4"],
      requires: ["m2"]
    },
    {
      id: "m4",
      campaignId: "crusader-trail",
      order: 4,
      title: "Krak Frontier",
      objective: "Fortify walls and survive mass archers.",
      enemy: "The Wolf",
      biome: "Plateau",
      difficulty: "Hard",
      x: 980,
      y: 610,
      next: ["m5"],
      requires: ["m3"]
    },
    {
      id: "m5",
      campaignId: "crusader-trail",
      order: 5,
      title: "Road to Jerusalem",
      objective: "Assault the citadel and defeat the enemy lord.",
      enemy: "Saladin",
      biome: "Holy City Outskirts",
      difficulty: "Hard",
      x: 1280,
      y: 560,
      next: ["m6"],
      requires: ["m4"]
    },
    {
      id: "m6",
      campaignId: "crusader-trail",
      order: 6,
      title: "Jerusalem",
      objective: "Final offensive with full economy pressure.",
      enemy: "The Wolf",
      biome: "Citadel",
      difficulty: "Very Hard",
      x: 1620,
      y: 520,
      next: ["m7"],
      requires: ["m5"]
    },
    {
      id: "m7",
      campaignId: "crusader-trail",
      order: 7,
      title: "Marsh of Acre",
      objective: "Control crossings and stop enemy flank raids.",
      enemy: "The Jackal",
      biome: "Coastal Wetlands",
      difficulty: "Very Hard",
      x: 1870,
      y: 600,
      next: ["m8"],
      requires: ["m6"]
    },
    {
      id: "m8",
      campaignId: "crusader-trail",
      order: 8,
      title: "Crown of the Levant",
      objective: "Break allied enemy keeps and hold central relic grounds.",
      enemy: "The Emperor",
      biome: "Mountain Crown",
      difficulty: "Legendary",
      x: 2060,
      y: 470,
      next: [],
      requires: ["m7"]
    }
  ]
};
