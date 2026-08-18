# VISION.md

# Minimal Health — Vision & Product Specification

> **Working concept:** a minimal Android app for tracking everyday movement without becoming a full fitness or health platform.

## 1. Product Vision

Build a small, focused Android app for people who want to answer a simple question:

> **"Quanto mi sono mosso oggi?"**

The app should provide the most useful movement metrics — primarily steps, active time, distance and estimated active calories — while deliberately avoiding the feature overload found in general-purpose health and fitness platforms.

The product is **not** intended to replace Google Fit, Samsung Health, Garmin Connect or a medical/clinical application.

Its purpose is narrower:

- count steps;
- summarize everyday movement;
- recognize or record continuous activities such as walks;
- provide a small number of understandable metrics;
- make historical data easy to consult;
- remain lightweight, calm and unobtrusive.

### Product promise

> **Everything you want to know about your daily movement. Nothing you don't need.**

---

# 2. Problem

Many users only need a few everyday health/activity metrics:

- How many steps did I take?
- How long was I active?
- How far did I walk?
- Approximately how many active calories did I burn?
- Did I go for a specific walk, and how long was it?
- How active have I been over the last few days?

Large health applications often answer these questions as part of much broader platforms containing nutrition, sleep, heart metrics, workouts, devices, social features, challenges, coaching and many other functions.

That breadth is useful for some users, but unnecessary for others.

This app should intentionally optimize for the second group.

---

# 3. Target User

The primary user is someone who:

- wants a simple step counter;
- occasionally wants distance and active time;
- may want estimated active calories;
- likes seeing individual walks/activities;
- does not want a complete fitness ecosystem;
- does not want to configure dozens of health metrics;
- prefers a quiet application with little cognitive overhead.

The user may have no wearable device. The phone should be sufficient for the core experience.

---

# 4. Product Principles

## 4.1 Minimal by default

Every feature must justify its presence.

The default experience should remain understandable without reading documentation.

If a feature is useful only to a small subset of users and makes the main interface more complicated, it should probably remain optional or be deferred.

## 4.2 Movement first

The app focuses on everyday movement, not on "health" in the broadest possible sense.

The core domain is:

- steps;
- walking/activity duration;
- distance;
- active calories;
- activity sessions.

## 4.3 Daily totals + individual activities

A central product concept is the distinction between:

### Daily totals

"What happened during the whole day?"

Example:

- 8,432 steps
- 6.1 km
- 74 active minutes
- 327 active kcal

### Individual activity sessions

"What happened during one continuous activity?"

Example:

- Walk — 09:32–10:18
- 4,820 steps
- 3.4 km
- 46 min
- 180 active kcal
- 4.4 km/h average speed

Both views are important and should coexist.

## 4.4 Passive first, manual when useful

The app should be useful without requiring the user to press Start every time they walk.

At the same time, users should be able to explicitly start a tracked activity when they want a dedicated session.

## 4.5 Battery-conscious

Continuous use of GPS should never be required for the basic experience.

Step counting should use the most appropriate low-power Android capabilities available.

GPS should be optional and primarily associated with explicitly tracked activities.

## 4.6 Transparent estimates

Metrics such as active calories are estimates.

The UI should avoid presenting estimates as medical-grade measurements.

Example wording:

> Active calories — estimated

## 4.7 No forced gamification

Goals, streaks, badges and challenges can be useful, but they must not dominate the product.

The app should feel useful even when the user has no goal.

---

# 5. Core Feature Set

## 5.1 Steps

### Description

The primary metric of the application.

The app should display:

- today's steps;
- steps during an individual activity;
- steps for previous days;
- average daily steps;
- weekly/monthly totals where useful.

### Android implementation

Prefer Android's step-related sensors where available:

- `TYPE_STEP_COUNTER`
- `TYPE_STEP_DETECTOR`

The implementation must detect sensor availability and provide an appropriate fallback strategy.

### Behavior

The main screen should make the current daily step count immediately visible.

Example:

```text
8,432
steps
```

An optional goal may be displayed as secondary information:

```text
8,432 / 10,000
```

The goal must not imply that 10,000 is universally required.

---

# 5.2 Active Time

### Description

The total amount of time during which the user is considered active.

Two levels must exist:

### Daily active time

A daily aggregate.

Example:

```text
74 min active
```

### Activity duration

The duration of one continuous activity.

Example:

```text
46 min
```

The two values must not be conflated.

### Behavior

Daily active time is the sum of qualifying activity periods.

Individual activity duration belongs to a single session.

The implementation should avoid double counting overlapping or duplicate sessions.

---

# 5.3 Distance

### Description

The estimated distance traveled through walking/activity.

The application should distinguish, where possible, between:

- distance estimated from steps/stride;
- distance measured using location/GPS.

### Daily

Example:

```text
6.1 km
```

### Activity

Example:

```text
3.4 km
```

### Behavior

GPS is not required for normal daily distance.

When GPS is explicitly enabled for an activity, the measured distance can be more accurate and should be associated with that activity.

---

# 5.4 Active Calories

### Description

Estimated calories burned through activity, not total daily energy expenditure.

The UI should consistently label this concept as:

> Active calories

or:

> Active calories (estimated)

### Inputs

Depending on implementation and available data, the estimate can use:

- body weight;
- age;
- sex, when relevant to the selected estimation model;
- activity duration;
- activity type;
- speed/intensity.

User profile information should be optional where possible.

### Daily

```text
327 kcal
```

### Activity

```text
180 kcal
```

The app must avoid implying clinical accuracy.

---

# 5.5 Activity Sessions

### Description

A continuous period of movement such as a walk.

A session can contain:

- start time;
- end time;
- duration;
- steps;
- distance;
- active calories;
- average speed;
- pace;
- average cadence;
- optional GPS route;
- optional elevation gain.

### Example

```text
Walking
09:32 — 10:18

46 min
4,820 steps
3.4 km
180 active kcal
4.4 km/h
```

---

# 5.6 Automatic Activity Detection

### Goal

Allow the app to identify meaningful continuous movement without requiring the user to manually start every activity.

### Behavior

The app may identify periods of sustained walking/movement and turn them into activity sessions.

Example:

```text
09:32–10:18
Walking
```

The implementation must be conservative.

False positives are worse than missing a trivial short movement period.

The user should be able to edit, merge, split or delete an activity when practical.

### MVP approach

Automatic detection can initially be limited to walking-like activity.

More sophisticated activity classification should be deferred.

---

# 5.7 Manual Activity Tracking

### Goal

Provide an explicit "Start activity" workflow.

### Start screen

Possible activity types:

- Walking
- Running
- Other

The MVP may start with only:

- Walking

### During activity

Show only essential real-time information:

```text
24:18

2,431 steps
1.7 km
4.2 km/h
112 kcal
```

Primary action:

> Stop

Secondary action:

> Pause

GPS should be optional.

---

# 5.8 Speed

### Description

Useful mainly during an individual activity.

Metrics:

- average speed;
- current speed, when available;
- maximum speed, when useful.

Example:

```text
Average speed
4.4 km/h
```

Speed should not dominate the daily dashboard.

---

# 5.9 Pace

### Description

Alternative presentation of walking/running speed.

Example:

```text
13:38 min/km
```

Pace and speed represent the same basic concept in different forms.

The app should avoid showing both everywhere.

A user preference may eventually allow one or the other.

---

# 5.10 Cadence

### Description

Steps per minute.

Example:

```text
108 steps/min
```

Useful primarily during a continuous activity.

Possible values:

- average cadence;
- minimum cadence;
- maximum cadence.

Cadence should remain secondary in the MVP UI.

---

# 5.11 Activity Intensity

### Description

Simple classification of activity intensity.

Possible levels:

- Light
- Moderate
- Vigorous

The product should avoid creating a complex training-zone system.

The purpose is simply to provide context.

Example:

```text
Moderate walking
42 min
```

---

# 5.12 Optional GPS Tracking

### Description

GPS is an enhancement for tracked activities, not a requirement for normal use.

When enabled, GPS can provide:

- more accurate distance;
- speed;
- route;
- location-based activity data;
- approximate elevation changes.

### Principle

Do not run GPS continuously just to count steps.

### UX

Starting a tracked activity may offer:

```text
Record route
[ Off ] [ On ]
```

Default should favor battery savings.

---

# 5.13 Route Map

### Description

When GPS tracking is enabled, show the route taken on a map.

The route belongs to the activity detail screen.

Example:

```text
Walking
5.2 km
58 min

[ Route map ]

4,930 steps
```

This feature should not affect the simplicity of the main dashboard.

---

# 5.14 Elevation Gain

### Description

Optional metric for outdoor activities.

Example:

```text
Elevation gain
84 m
```

Potentially derived from:

- GPS altitude;
- barometer, when available;
- other Android/location sources.

Because elevation can be noisy, it should be presented as an estimate.

This is a later-phase feature.

---

# 5.15 Daily Goals

### Description

Allow the user to define one simple daily target.

Possible target types:

- steps;
- active minutes;
- distance;
- active calories.

The user may also choose:

> No goal

### Principle

Goals should be optional.

The product must remain fully functional without them.

---

# 5.16 Goal Progress

Examples:

```text
8,432 / 10,000 steps
```

or:

```text
42 / 60 active min
```

Progress should be visible but never dominate the screen.

Avoid guilt-oriented messaging.

Prefer neutral language:

> 84% complete

rather than:

> You're falling behind!

---

# 5.17 History

### Description

A simple historical overview.

### Day view

```text
Today

8,432 steps
6.1 km
74 min
327 kcal
```

### Week view

Show daily totals for:

- steps;
- active minutes;
- distance;
- active calories.

### Month view

Optional later feature.

The product should prefer clarity over analytics density.

---

# 5.18 Activity Timeline

### Description

A visual timeline of activity throughout the day.

Example:

```text
06   08   10   12   14   16   18   20
     ███████        ██       █████
```

The goal is to answer:

> "When was I active today?"

This may be more useful than complex charts.

---

# 5.19 Averages

Useful simple statistics:

- average daily steps;
- average active minutes;
- average distance;
- average active calories.

Possible ranges:

- 7 days;
- 30 days.

Avoid excessive statistical analysis in the main product.

---

# 5.20 Personal Records

Optional lightweight statistics:

- highest steps in a day;
- longest activity;
- longest distance;
- highest active-calorie day.

Example:

```text
Best day
14,823 steps
```

Records should be informational rather than aggressively gamified.

---

# 5.21 Streaks

Optional.

Example:

```text
6-day streak
```

The feature should remain secondary.

A user should never feel that breaking a streak means they have failed.

---

# 6. Main Information Architecture

The recommended product structure is:

## Today

The default screen.

Primary metrics:

```text
Steps
8,432

6.1 km     74 min     327 kcal
```

Then:

```text
Today's activity
```

followed by individual sessions.

Then optional:

```text
Goal
8,432 / 10,000
```

## Activity Detail

Shows one continuous activity.

Example:

```text
Walking

09:32 — 10:18
46 min

4,820 steps
3.4 km
180 kcal

4.4 km/h
108 steps/min
```

Optional map.

## History

Day/week/month summaries.

## Settings

Only essential settings and permissions.

---

# 7. Recommended MVP

The first release should be intentionally small.

## MVP Features

1. Step counting
2. Daily step total
3. Daily active time
4. Daily estimated active calories
5. Daily estimated distance
6. Automatic walking/activity sessions
7. Manual activity start/stop
8. Activity detail screen
9. Basic history
10. Optional daily step goal
11. Health Connect integration
12. Sensor availability handling
13. Battery-conscious background operation

## Defer from MVP

- advanced running analytics;
- heart-rate monitoring;
- sleep;
- nutrition;
- water tracking;
- body measurements;
- blood pressure;
- blood glucose;
- SpO2;
- ECG;
- medication;
- menstrual/reproductive health;
- stress;
- social features;
- challenges;
- coaching;
- smartwatch-specific features;
- advanced training plans;
- complex graphs;
- extensive gamification.

The reason is not that these features are bad. They are simply outside the product's core promise.

---

# 8. Android Data & Sensor Strategy

## 8.1 Step Sensors

Use Android's native step capabilities when available:

- `TYPE_STEP_COUNTER`
- `TYPE_STEP_DETECTOR`

The implementation must account for devices where the required sensor is unavailable.

Do not assume all Android devices expose the same sensors.

## 8.2 Accelerometer

Potential use cases:

- fallback movement detection;
- activity recognition support;
- distinguishing meaningful movement from inactivity.

The accelerometer should not be continuously sampled at unnecessarily high frequencies.

## 8.3 Location

Use location only when required for:

- explicitly recorded activities;
- route recording;
- accurate activity distance/speed.

## 8.4 Barometer

Optional sensor.

Potential use:

- elevation changes.

Must be treated as optional hardware.

---

# 9. Health Connect Strategy

Health Connect should be considered a first-class integration layer.

The app may:

- read relevant health/activity data;
- write the app's recorded activity data;
- synchronize where appropriate;
- coexist with data coming from other Android apps/devices.

Relevant categories include:

- steps;
- distance;
- active calories;
- activity intensity;
- speed;
- cadence;
- elevation;
- exercise/activity sessions.

The app should not expose every Health Connect data type simply because the platform supports it.

Health Connect is an interoperability mechanism, not a reason to expand the product scope.

---

# 10. Data Model Concept

The domain should conceptually distinguish between **daily aggregates** and **activity sessions**.

## DaySummary

Possible fields:

- date;
- total steps;
- total active duration;
- total distance;
- total active calories;
- number of activities.

## ActivitySession

Possible fields:

- id;
- start time;
- end time;
- activity type;
- duration;
- steps;
- distance;
- active calories;
- average speed;
- average pace;
- average cadence;
- intensity;
- GPS enabled;
- route;
- elevation gain.

## UserProfile

Minimal optional profile information:

- weight;
- age;
- sex;
- preferred units.

Avoid collecting information that is not required by a feature.

---

# 11. Units

The application should support:

## Metric

- kilometers;
- meters;
- km/h;
- min/km;
- kilograms;
- kcal.

## Imperial

Optional later:

- miles;
- mph;
- pounds.

The user's unit preference should affect presentation, not the internal domain model.

---

# 12. Permissions

Permissions should be requested only when a feature actually needs them.

Examples:

- Activity recognition / sensor-related permissions for relevant functionality;
- Health Connect permissions for Health Connect integration;
- Location only for route tracking.

Never request GPS/location permission solely to count steps.

The app should clearly explain why a permission is needed before asking for it where appropriate.

---

# 13. Notifications

Notifications should be minimal.

Potential useful notifications:

- activity started, only when relevant;
- activity completed;
- optional daily goal reached.

Avoid persistent promotional or motivational notifications.

The user should be able to disable all non-essential notifications.

---

# 14. Battery Philosophy

Battery efficiency is a product feature.

Rules:

1. Prefer hardware step counting where available.
2. Avoid continuous GPS in the background.
3. Avoid unnecessary high-frequency sensor sampling.
4. Batch non-critical work when possible.
5. Perform historical calculations efficiently.
6. Do not wake the device unnecessarily.

A step counter app that consumes significant battery contradicts the product's purpose.

---

# 15. Privacy Philosophy

Health/activity data is sensitive.

The default architecture should minimize data collection.

Principles:

- collect only what is necessary;
- do not send health/activity data to a server unless there is a compelling product reason;
- favor local storage;
- clearly explain Health Connect permissions;
- clearly explain location usage;
- allow users to delete their local app data;
- avoid accounts in the MVP unless required.

### Product stance

> The app should work without requiring an account.

Cloud synchronization can be considered later.

---

# 16. UX Principles

## One glance

The user should understand today's activity in a few seconds.

## No dashboard overload

The main screen should contain a small number of primary metrics.

## Progressive disclosure

Advanced metrics appear only when relevant.

Example:

Daily screen:

```text
8,432 steps
6.1 km
74 min
327 kcal
```

Activity detail:

```text
4.4 km/h
108 steps/min
+32 m
```

## Calm visual language

Avoid:

- excessive badges;
- flashing elements;
- aggressive achievement messaging;
- unnecessary animations;
- cluttered cards.

## Accessible numbers

Use large typography for primary metrics.

---

# 17. Suggested Today Screen

A possible first design:

```text
TODAY

          8,432
           steps

   6.1 km   ·   74 min   ·   327 kcal

GOAL
████████████████░░░░ 84%

ACTIVITY

09:32
Walking
46 min · 4,820 steps · 3.4 km

18:04
Walking
23 min · 2,140 steps · 1.5 km
```

A floating/primary action may provide:

> Start activity

The interface should remain useful even if there are no recorded activity sessions.

---

# 18. Suggested Activity Screen

```text
WALKING

24:18

2,431 steps
1.7 km
112 kcal

4.2 km/h
108 steps/min

[ Stop ]
```

Optional:

```text
[ Record route ]
```

The screen must not become a cycling/running watch-style dashboard.

---

# 19. Behavior Rules

## Daily totals

Daily totals are the authoritative summary for a calendar day.

They may be derived from:

- phone sensors;
- locally recorded activities;
- Health Connect data;
- synchronized sources.

The implementation must prevent duplicate contribution when the same underlying data is available through multiple sources.

## Activity sessions

Activities represent meaningful continuous periods.

Very short movement bursts should generally not become standalone activities.

## Corrections

The user should eventually be able to:

- delete a session;
- edit activity type;
- adjust start/end times;
- correct obvious data errors.

These features may be post-MVP.

---

# 20. Error & Edge Cases

The app must handle:

- no step sensor;
- step sensor temporarily unavailable;
- Health Connect unavailable;
- permissions denied;
- location disabled;
- GPS unavailable;
- no activity today;
- phone reboot;
- time zone changes;
- daylight saving time changes;
- duplicate data;
- incomplete sessions;
- missing user profile information;
- battery saver restrictions.

The product should degrade gracefully.

Example:

> Step counting isn't available on this device.

rather than presenting a broken dashboard.

---

# 21. Data Quality

The app should prefer **honest uncertainty** over false precision.

For example:

Prefer:

```text
6.1 km
```

over:

```text
6.137284 km
```

Prefer:

```text
~327 active kcal
```

where appropriate.

Do not imply medical or scientific precision where none exists.

---

# 22. What the Product Is Not

This is deliberately not:

- a medical application;
- a hospital/clinical platform;
- a complete health dashboard;
- a nutrition tracker;
- a smartwatch companion suite;
- a personal trainer;
- a social fitness network;
- a replacement for specialized sports computers.

This distinction should guide every future feature request.

When a proposed feature starts pushing the product toward one of these categories, reconsider whether it belongs.

---

# 23. Future Features

Potential post-MVP additions, ordered roughly by product fit:

### Very strong fit

- improved automatic activity detection;
- better walking session detection;
- weekly summaries;
- 30-day averages;
- activity timeline;
- personal records;
- configurable goals;
- pace/speed preference;
- activity editing;
- widgets;
- notification-based daily summary;
- Wear OS companion.

### Good fit, but optional

- GPS route recording;
- elevation;
- running;
- cycling;
- simple hiking mode;
- more detailed charts;
- streaks;
- Health Connect two-way synchronization improvements.

### Likely outside the core philosophy

- sleep;
- nutrition;
- water;
- detailed body composition;
- advanced heart-health dashboards;
- medication;
- coaching;
- social feeds;
- community challenges;
- complex training plans.

---

# 24. Development Priorities

The implementation should progress in this order:

## Phase 1 — Core counter

- Android project setup;
- step sensor integration;
- daily step count;
- local persistence;
- basic Today screen.

## Phase 2 — Daily movement summary

- active time;
- distance;
- active calorie estimation;
- history;
- configurable units;
- basic daily goal.

## Phase 3 — Activities

- automatic walking session detection;
- activity persistence;
- activity detail screen;
- manual start/stop.

## Phase 4 — Health Connect

- permissions;
- read relevant data;
- write supported records;
- deduplication/data-source strategy.

## Phase 5 — Optional GPS

- route recording;
- map;
- improved distance/speed;
- elevation.

## Phase 6 — Refinement

- widgets;
- better history;
- accessibility;
- battery optimization;
- edge-case handling;
- polished onboarding.

---

# 25. Definition of a Successful First Release

The first public version is successful if a user can:

1. Install the app.
2. Grant only the necessary permissions.
3. Open it and immediately see today's steps.
4. See a small summary of distance, active time and active calories.
5. Leave the app running without worrying about battery consumption.
6. Return later and understand what happened during the day.
7. Open an individual walk and see its duration and core metrics.
8. Manually record a walk when desired.
9. See a simple history of previous days.
10. Use the app without creating an account.

The user should never need to understand the underlying technical architecture.

---

# 26. Product Success Criteria

The most important product questions are not:

> How many features do we have?

They are:

> How quickly can the user understand today's activity?

> How accurate and trustworthy do the numbers feel?

> Does the app stay out of the user's way?

> Does it consume very little battery?

> Does it remain useful without configuration?

> Does adding a feature make the app better, or simply bigger?

A successful product is one that does less — but does it exceptionally well.

---

# 27. North Star

The product should always be able to reduce its value proposition to this:

> **A simple movement tracker for Android.**
>
> **Steps, time, distance and active calories — plus the walks you actually took.**
>
> **No unnecessary health dashboard. No feature overload.**

If a future feature cannot strengthen this promise, it should be questioned before implementation.

