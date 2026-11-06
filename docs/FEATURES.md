# TalkReel AR features

## v1, MVP, P0
1. Talk and record teleprompter, starts with video, pause and resume together
2. Live AR filters
3. AR virtual background
4. Auto captions, karaoke style
5. Auto cut silences
6. Trending sounds
7. One tap export, 9:16, 1080x1920

## v2, P1
8. One tap hook, first 3 seconds
9. Smart face tracking zoom
10. Hands free recording
11. Reviewer Mode, AR labels on a product
12. AR 3D props
13. AR before and after
14. Auto B roll

## v3, P2
15. AR try on for reviewers
16. Voice changer and clean audio
17. Viral score before posting
18. Daily trend challenges
19. Duet and reaction mode
20. Auto post and schedule

## Teleprompter, detailed
- Starts on Record, 3, 2, 1 countdown
- Tap to pause, tap to resume, jump back 2 lines
- Auto pause after 3 seconds of silence
- Speed follows voice in Auto mode
- Text under the camera lens, current line bright
- Prompts Mode for confident creators, keywords only
- Shy Mode, only 2 lines at a time

## Backend
- GET /api/trends
- GET /api/templates
- POST /api/viral-score
- Free host: Supabase Free, then Cloudflare Workers Free
