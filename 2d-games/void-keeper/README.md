# PROJECT: VOID-KEEPER

An atmospheric, top-down survival incremental game built with HTML5 Canvas and JavaScript.

**Version:** v1.3 (Stable)  
**Platform:** Web (HTML5/JavaScript)  
**Theme:** Sci-Fi Horror / Survival

## Overview

VOID-KEEPER is a survival game where you must maintain a lighthouse beacon against an encroaching darkness. Your mission: keep the light burning, scavenge for resources, and survive as long as possible.

## Gameplay

- **Survive:** Your lighthouse burns Carbon fuel. If it reaches zero, you're consumed by the darkness.
- **Scavenge:** The fog hides valuable debris. Use your light beam to reveal resources hidden in the darkness.
- **Focus Mode:** Hold **Left Mouse Button** to stop rotation and narrow your beam. This increases burn power, allowing you to reach distant objects.
- **Collect:** Click on revealed debris to harvest Carbon (fuel) or Scrap (currency).
- **Upgrade:** Spend Scrap to improve your Lens, Gear (rotation speed), and Focus capabilities.

## Features

- **3-Layer Canvas Rendering System** for volumetric lighting and fog effects
- **Dynamic Fog System** that creeps in and can be burned back
- **Atmospheric Audio** using Web Audio API
- **Lore System** with unlockable log entries based on survival time
- **Settings Menu** with volume controls, graphics quality, and resolution scaling
- **Particle Effects** for visual feedback
- **Touch & Mouse Support** for cross-platform play

## Controls

- **Left Mouse Button / Touch:** Hold to activate Focus Mode (stops rotation, narrows beam)
- **Click/Tap on Debris:** Collect revealed resources
- **Settings Gear Icon:** Access game settings
- **Manual Button (📖):** View in-game manual and lore

## Installation

Simply open `index.html` in a modern web browser. No build process or dependencies required.

## Technical Details

- Single HTML file containing all CSS, JavaScript, and game logic
- Uses Canvas API for rendering
- LocalStorage for save data
- Responsive design with resolution scaling options

## Browser Compatibility

Works in all modern browsers that support:
- HTML5 Canvas
- Web Audio API
- LocalStorage
- ES6+ JavaScript

---

**Status:** STABLE // SYSTEM READY
