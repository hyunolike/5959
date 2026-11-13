# 마음 산책 캐릭터 프레임

- 생성 방식: 내장 image_gen. 기존 오구와 불안 몬스터 그림을 참조했다.
- 최종 자산: `apps/web/public/brand/runner/ogu-walk-atlas.png`
- 크기: 1536×1024 RGBA. 256×256 셀 6열 4행.
- 확인: 실제 alpha=0인 배경 픽셀 1,018,092개. 오구, 동료, 장애물을 함께 담았으며 투명 PNG를 그대로 보존한다.
- 행 1: 달리기 4프레임, 점프 2프레임. 행 2: 정지/쉬기/착지. 행 3: 동료. 행 4: 돌과 나무토막.
- 렌더러의 장애물 경계는 원본 PNG에서 측정했다. 돌 (65,944,126,57), 나무토막 (547,918,188,85). 투명 여백을 제외한 높이를 충돌 높이에 맞춘다.

## 생성 프롬프트

Reference image 1: `apps/web/public/brand/ogu-hello.webp`

Reference image 2: `apps/web/public/monsters/anxiety-full.webp`

```text
Create a production 2D game sprite atlas on a truly TRANSPARENT background. References: image 1 is Ogu, preserve exact cream round body, little ears pink inside, one green leaf on head, navy hand-drawn outlines, tiny limbs and sweet expression. Image 2 is the small purple furry anxiety companion, preserve purple fur and round eyes, but make expression calmer. Full canvas landscape 1536x1024, EXACT uniform 6 columns by 4 rows, 24 tiles each 256x256, NO gutters, NO grid lines, NO text, NO labels, NO ground shadows. Each sprite fully inside its tile, transparent surroundings. All Ogu frames face RIGHT in a three-quarter side view, identical head/body sizes and center x, feet baseline at 224px within tile, character height ~190px. Row1 columns1-4: Ogu 4 readable different RUN cycle poses, feet alternating stepping, body consistent; columns5-6 Ogu in the air jumping with feet tucked, first rising then falling. Row2 columns1-2 Ogu standing idle side view; columns3-4 Ogu sitting resting gently; columns5-6 Ogu landing pose and standing happy. Row3 columns1-4 purple fluffy companion waddling RUN cycle facing right, same baseline, companion height ~150px; columns5-6 companion airborne then resting. Row4 columns1-2 two small grey rounded pebbles, each about100x70px lower-center baseline224; columns3-4 two short natural tan wooden logs, each about145x65px lower-center baseline224, simple same illustrated style; columns5-6 simple small grey pebbles variations. Clean bold outlines, flat warm illustration, legible as 48px sprites, no pixel art, no scenery. These are individually separable atlas cells for a side-scrolling runner. Preserve identity rather than inventing a different character.
```

## 투명 배경 정리 프롬프트

첫 결과에는 반투명 배경이 남아, 같은 생성 도구로 배경만 정리했다. 프레임 위치와 그림을 유지했다.

```text
Precisely remove the ENTIRE white/light gray canvas background and ALL drop shadows/glow from this game sprite atlas. Every empty pixel between and around the characters and objects must have alpha=0, fully transparent. Keep all 24 sprites, their 6 columns and 4 rows, EXACT positions, identities, colors, outlines and dimensions unchanged. Preserve cream body white areas and eyes inside outlined characters, not background. Output same 1536x1024 PNG. No opaque backdrop, no gray halo, no new grid, no labels. A true clean transparent game sprite sheet ready to draw over a colored Canvas background.
```
