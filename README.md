# TheWalls — командный режим со стенами

**TheWalls** — режим на 4 команды: сначала у каждой команды свой закрытый сектор (фарм/крафт), затем стены падают, центр открывается и начинается полноценная PvP-битва до победы одной команды.

Плагин встроен в нашу инфраструктуру **MiniGamesAPI** (очередь, /ready, /teamselect, лобби-предметы, инстансы) и использует **Multiverse-Core** для клонирования миров арен.

---

## Возможности

- **Лайфцикл матча:** WAITING → COUNTDOWN → RUNNING → ENDING → CLEANUP.
- **4 команды:** ORANGE / BLUE / PINK / GREEN.
- **Сценарии через `scenario-config.yml`:** любое число фаз, с таймингами и флагами:
  - включение/выключение PvP,
  - блокировка стен,
  - блокировка центра,
  - разрушение стен в начале фазы,
  - (опционально) сжатие worldborder по фазам.
- **Сектора команд на стадии подготовки:** игроки не могут покинуть свой сектор, пока это запрещено сценарием.
- **Открытие стен:** заданные регионы стен удаляются постепенно (батчами), чтобы не повесить тик.
- **Центр:** пока центр закрыт — вход запрещён, если кто-то оказался внутри — его выталкивает.
- **Хранители (guardians) команд:** Illusioner с жизнями/респавном; после исчерпания жизней команда теряет респавны.
  - есть «последний шанс»: если хранитель был сломан во время death-screen, игрок всё равно получает один финальный респавн.
- **Авто-респавн и режим наблюдателя:** без кнопки «Возродиться»; при задержке респавна игрок временно уходит в spectator.
- **Регенерация руд (скан карты):** плагин сам находит все блоки руд на карте матча, при добыче заменяет на `depleted`, а затем восстанавливает через заданное время.
- **Ускоренные печки:** множитель скорости плавки (для всего матча).
- **Сбор результатов через MiniGamesAPI:** команды/игроки/метрики (kills, alive_end, guardian_lives_end, end_reason и т.д.).
- **Клонирование и чистка миров:**
  - матчи создаются в мирах вида `tw_game_<arenaId>_<n>`,
  - (опционально) церемония — `tw_ceremony_...`,
  - при включении/выключении плагин чистит «осиротевшие» клоны после крашей.

---

## Требования

- Paper/Purpur **1.21.x**
- **Multiverse-Core** (клонирование миров)
- Плагин использует встроенную инфраструктуру **MiniGamesAPI** (инициализируется внутри TheWalls).

---

## Установка

1. Установи **Multiverse-Core**.
2. Скопируй `TheWalls-<version>.jar` в `plugins/`.
3. Подготовь миры:
   - **лобби-мир** (например `lobby`),
   - **template-world** для каждой арены (например `tw_map_01`, `tw_map_02`, …) — эти миры должны быть **загружены** на сервере (через Multiverse import/автозагрузку),
   - (опционально) **мир-шаблон церемонии** (см. `ceremony.template-world`).
4. Запусти сервер — плагин создаст файлы:
   - `plugins/TheWalls/config.yml`
   - `plugins/TheWalls/scenario-config.yml`
   - `plugins/TheWalls/ore-config.yml`
   - `plugins/TheWalls/minigamesapi/…` (конфиги инфраструктуры)
5. Настрой `config.yml` (арены/координаты), затем перезапусти сервер.

---

## Конфигурация

### `plugins/TheWalls/config.yml`

Файл создаётся из `src/main/resources/config.yml` и дополняется дефолтами при первом запуске.

> В репозитории может присутствовать блок `furnace-speed:` — это **легаси** и в текущем коде не используется.

Минимальный пример с одной ареной:

```yml
lobby:
  world: "lobby"
  spawn: "0.5, 65, 0.5, 0, 0"

players-per-team: 4

match:
  countdown-seconds: 10
  total-seconds: 900
  build-seconds: 600
  difficulty: "NORMAL"

  walls:
    # Сколько блоков стены удалять за тик при открытии.
    blocks-per-tick: 8000

    # Блоки, которые НЕ будут удалены даже если они попали в регион стены.
    keep-blocks: []

  rules:
    friendly-fire: false
    pvp-in-build: false

    # Запрещённые к ломанию/строительству блоки (на случай, если они встречаются на карте).
    protected-blocks:
      - BEDROCK
      - BARRIER
      - STRUCTURE_BLOCK
      - STRUCTURE_VOID
      - JIGSAW
      - COMMAND_BLOCK
      - CHAIN_COMMAND_BLOCK
      - REPEATING_COMMAND_BLOCK

guardians:
  enabled: true
  lives: 3
  respawn-seconds: 10
  max-health: 40.0
  name: "&6Хранитель"

respawn:
  # Задержка респавна (в секундах). Если spectator-mode=true и delay>0, игрок временно станет наблюдателем.
  delay-seconds: 5
  spectator-mode: true

fast-furnace:
  enabled: true
  speed-multiplier: 3.0

ceremony:
  enabled: false
  template-world: "tw_ceremony"
  duration-seconds: 12

  # 4 подиума (места 1-4). Формат списка:
  # [minX, y, minZ, maxX, maxZ, yaw, pitch]
  podiums: []

arenas:
  - id: "tw-01"

    # Какой мир клонировать для матча.
    template-world: "tw_map_01"

    # Сколько одновременных клонов этой арены разрешить (pool) в матчмейкинге.
    pool-size: 2

    team-spawns:
      ORANGE: "100.5, 65, 0.5, 90, 0"
      BLUE:   "-100.5, 65, 0.5, -90, 0"
      PINK:   "0.5, 65, 100.5, 180, 0"
      GREEN:  "0.5, 65, -100.5, 0, 0"

    # Спавны хранителей. Если для команды не задано — используется team-spawns как fallback.
    guardian-spawns: {}

    # Сектора команд (ограничение движения на стадии подготовки).
    # Формат: "x1, y1, z1, x2, y2, z2"
    team-sectors:
      ORANGE: "80, 0, -40, 120, 255, 40"
      BLUE:   "-120, 0, -40, -80, 255, 40"
      PINK:   "-40, 0, 80, 40, 255, 120"
      GREEN:  "-40, 0, -120, 40, 255, -80"

    center:
      point: "0.5, 65, 0.5"
      radius: 12.0

    # Регионы стен, которые блокируют команды в BUILD и удаляются при открытии.
    walls:
      - "-5, 0, -64, 5, 255, 64"
      - "-64, 0, -5, 64, 255, 5"

    # Постоянные стены границы (не ломаются и не открываются).
    boundary-walls: []

    # (Опционально) Настройка vanilla world border для арены.
    border:
      size: 256.0
      center: "0.5, 65, 0.5"
      damage-buffer: 0.0
      damage-amount: 2.0
      warning-distance: 5
      warning-time: 10
```

Ключевые моменты:

- `match.total-seconds` — общий лимит матча. Если в `scenario-config.yml` задан сценарий с фазами, итоговая длительность берётся из фаз (см. ниже).
- `match.build-seconds` используется только для дефолтного сценария (если `scenario-config.yml` пустой/битый).
- `arenas[].team-spawns`, `team-sectors`, `walls`, `center` — база для корректной работы ограничений.
- `pool-size` влияет на матчмейкинг (сколько одновременных игр на одном `id`).

---

### `plugins/TheWalls/scenario-config.yml`

Файл создаётся автоматически при первом запуске (если отсутствует). Позволяет полностью управлять «фазами» матча.

Пример:

```yml
ores:
  # Глобальные флаги: можно ли генерировать руды и можно ли их добывать.
  generate: true
  mine: true

phases:
  build:
    order: 1
    name: "Подготовка"

    # Можно задавать либо duration (в секундах), либо end-at-second (абсолютная секунда от старта).
    duration: 600

    pvp-enabled: false
    walls-locked: true
    center-locked: true

    # Если true — при входе в фазу стены начнут удаляться (AIR) постепенно.
    break-walls-on-start: false

    # (Опционально) по фазам сжимать worldborder (работает только если border настроен на арене).
    border-shrink: false
    border-shrink-speed: 0.1
    border-final-size: 20.0

    start-title: "Подготовка"
    start-subtitle: "Стены и центр закрыты"
    start-message: ""

  open:
    order: 2
    name: "Битва"
    duration: 300

    pvp-enabled: true
    walls-locked: false
    center-locked: false
    break-walls-on-start: true

    start-title: "Стены разрушены!"
    start-subtitle: "Центр открыт"
    start-message: ""
```

Примечания:

- Итоговая длительность матча = конец последней фазы (с учётом `end-at-second`).
- Если `phases` пустой или отсутствует, плагин использует дефолт (две фазы) на основе `match.*` из `config.yml`.

---

### `plugins/TheWalls/ore-config.yml`

Настраивает руды, их «depleted»-замену и время восстановления.

```yml
settings:
  # Общий множитель скорости восстановления (1.0 = как задано в respawn-seconds)
  speed-multiplier: 1.0

ores:
  coal:
    depleted: TUFF
    respawn-seconds: 45
  iron:
    depleted: ANDESITE
    respawn-seconds: 67
  gold:
    depleted: DIORITE
    respawn-seconds: 95
  copper:
    depleted: GRANITE
    respawn-seconds: 57
  redstone:
    depleted: COBBLED_DEEPSLATE
    respawn-seconds: 77
  diamond:
    depleted: DEEPSLATE
    respawn-seconds: 150
```

Технически:

- В начале матча плагин сканирует клон мира и запоминает все блоки руд.
- При добыче руда заменяется на `depleted`, затем восстанавливается через `respawn-seconds / speed-multiplier`.
- Для оптимизации скан ограничивается:
  - сначала **world border** арены (если он настроен),
  - иначе — объединением регионов `team-sectors + walls`,
  - иначе — fallback (может быть тяжелее на больших мирах).

---

## Команды

### Админ

`/tw` (Paper Brigadier)

- `/tw status` — состояние матча (фаза/время/guardians/respawn)
- `/tw phase list` — список фаз сценария
- `/tw phase set <index>` — переключить текущую фазу
- `/tw phase next` — следующая фаза
- `/tw phase open` — принудительно открыть стены/центр (OPEN)
- `/tw phase build <seconds>` — задать оставшееся время BUILD (legacy-хелпер)
- `/tw time set <seconds>` — задать оставшееся общее время матча
- `/tw guardian kill <team>` — убить хранителя команды
- `/tw guardian respawn <team>` — зареспавнить хранителя (если жизни > 0)
- `/tw guardian lives <team> <lives>` — установить жизни хранителя
- `/tw respawn on|off <team>` — включить/выключить респавн команды
- `/tw end [team]` — закончить матч (по тайм-лимиту или с победителем)
- `/tw tp <team>` — телепортироваться на спавн команды (только если вы сами в матче)

Права:

- `thewalls.admin` (или `minigamesapi.admin`, или OP)

### Игроки

Команды/GUI лобби (очередь, готовность, выбор команды) предоставляет **MiniGamesAPI**.

---

## Как проверить (быстрый чеклист)

1. Запусти сервер, убедись что создались `config.yml`, `scenario-config.yml`, `ore-config.yml`.
2. Добавь хотя бы одну арену в `config.yml` и перезапусти.
3. Убедись, что template-world арены загружен (Bukkit видит `Bukkit.getWorld("tw_map_01")`).
4. Собери 8+ игроков, зайдите в очередь, нажмите `/ready`.
5. В BUILD попробуй выйти из сектора/войти в центр — должно блокировать.
6. На старте OPEN стены должны удалиться, PvP включится.
7. Убить хранителя — проверить жизни/респавн команды.
8. После матча игроки должны вернуться в лобби, а клон мира `tw_game_*` удалиться.
