# Smart comfort presets

AAS 1.5.1 handles common temperature complaints locally, before OpenAI.
The preset is expanded into ordinary `VoiceCommand` objects and executed by the
same dispatcher as explicit commands.

## Russian examples

- `Мне холодно`
- `Мне очень холодно`
- `Я замерзаю`
- `Сделай теплее`
- `Мне жарко`
- `Мне очень жарко`
- `Я плавлюсь`
- `Сделай прохладнее`

## Ukrainian examples

- `Мені холодно`
- `Мені дуже холодно`
- `Я змерзаю`
- `Зроби тепліше`
- `Мені спекотно`
- `Мені дуже спекотно`
- `Я плавлюся`
- `Зроби прохолодніше`

## Presets

### VERY_COLD

- climate on;
- ventilation-only mode off;
- automatic climate off, so the requested fan level is not overridden;
- seat ventilation off;
- temperature 28 °C;
- cabin fan level 3;
- heating of all AAS-supported seats at level 2;
- steering-wheel heating at level 1.

### VERY_HOT

- climate on;
- ventilation-only mode off, so active cooling is available;
- front and rear defrost off;
- heating of all AAS-supported seats off;
- steering-wheel heating off;
- temperature 20 °C;
- ventilation of all AAS-supported seats at level 2.

### COLD

Milder default used for `Мне холодно`:

- climate 25 °C;
- cabin fan level 2;
- seat heating level 1;
- steering-wheel heating level 1;
- seat ventilation off.

### HOT

Milder default used for `Мне жарко`:

- climate 22 °C;
- cabin fan level 2;
- seat ventilation level 1;
- seat heating, steering heating and defrost off.

`Seat.ALL` currently means the driver and front-passenger seats because those
are the validated seat channels present in the current BYDMate integration.

## Routing guarantees

- A pure comfort phrase uses `route=local-smart-comfort` and never calls OpenAI.
- A mixed recognized phrase such as `Мне жарко и включи музыку` is expanded and
  executed as a local command chain.
- If another clause is unknown, AAS sends the complete original transcript to
  OpenAI instead of executing only the recognized part.
- Questions and negated statements such as `Почему мне жарко?` or
  `Мне не холодно` do not activate a preset.
