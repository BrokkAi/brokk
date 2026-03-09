"""BROKK DEFENSE easter egg - fully playable terminal game.

Activated by typing  !-!-!  while the context modal is open.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

from rich.text import Text
from textual import events
from textual.app import ComposeResult
from textual.binding import Binding
from textual.screen import ModalScreen
from textual.widgets import Static

# ---------------------------------------------------------------------------
# Game constants
# ---------------------------------------------------------------------------
GAME_W: int = 80  # canvas width in characters
GAME_H: int = 22  # canvas rows (game area, excluding header/footer)
CITY_ROW: int = GAME_H - 1  # 0-indexed row where cities/bases live

# 6 cities: 3 left of centre, 3 right (leaving room for missile bases)
CITY_X: List[int] = [6, 14, 22, 56, 64, 72]

# 3 missile bases: left, centre, right
BASE_X: List[int] = [0, GAME_W // 2, GAME_W - 1]

PLAYER_SPEED: float = 1.8
EXPLOSION_MAX_R: float = 4.5
EXPLOSION_GROW: float = 0.4
EXPLOSION_SHRINK: float = 0.25

BASE_AMMO: int = 10  # starting missiles per base
CURSOR_STEP: int = 2  # cells moved per key press


# ---------------------------------------------------------------------------
# Entity dataclasses
# ---------------------------------------------------------------------------


@dataclass
class EnemyMissile:
    ox: float  # spawn x (for trail rendering)
    x: float
    y: float = 0.0
    tx: float = 0.0  # target x
    ty: float = float(CITY_ROW)
    speed: float = 0.15
    alive: bool = True

    def _dist(self) -> float:
        return math.hypot(self.tx - self.x, self.ty - self.y)

    def step(self) -> None:
        d = self._dist()
        if d < self.speed:
            self.x, self.y = self.tx, self.ty
        else:
            self.x += (self.tx - self.x) / d * self.speed
            self.y += (self.ty - self.y) / d * self.speed

    def at_target(self) -> bool:
        return self._dist() < self.speed + 0.1


@dataclass
class PlayerMissile:
    x: float
    y: float
    tx: float
    ty: float
    trail: List[Tuple[int, int]] = field(default_factory=list)
    alive: bool = True

    def _dist(self) -> float:
        return math.hypot(self.tx - self.x, self.ty - self.y)

    def step(self) -> None:
        d = self._dist()
        if d < PLAYER_SPEED:
            self.x, self.y = self.tx, self.ty
            self.alive = False
            return
        self.trail.append((int(round(self.x)), int(round(self.y))))
        if len(self.trail) > 15:
            self.trail.pop(0)
        self.x += (self.tx - self.x) / d * PLAYER_SPEED
        self.y += (self.ty - self.y) / d * PLAYER_SPEED


@dataclass
class Explosion:
    x: float
    y: float
    radius: float = 0.0
    expanding: bool = True

    @property
    def done(self) -> bool:
        return not self.expanding and self.radius <= 0.0

    def step(self) -> None:
        if self.expanding:
            self.radius += EXPLOSION_GROW
            if self.radius >= EXPLOSION_MAX_R:
                self.expanding = False
        else:
            self.radius = max(0.0, self.radius - EXPLOSION_SHRINK)

    def contains(self, x: float, y: float) -> bool:
        return math.hypot(x - self.x, y - self.y) <= self.radius


# ---------------------------------------------------------------------------
# Game state
# ---------------------------------------------------------------------------


class BrokkDefenseState:
    def __init__(self) -> None:
        self.cities: List[bool] = [True] * 6
        self.bases_ammo: List[int] = [BASE_AMMO] * 3
        self.enemy_missiles: List[EnemyMissile] = []
        self.player_missiles: List[PlayerMissile] = []
        self.explosions: List[Explosion] = []
        self.score: int = 0
        self.wave: int = 1
        self.game_over: bool = False
        self.wave_clear: bool = False
        self._wave_clear_ticks: int = 0
        self._spawn_wave()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def fire(self, cursor_x: int, cursor_y: int) -> None:
        """Fire from the nearest base with ammo toward (cursor_x, cursor_y)."""
        best: Optional[int] = min(
            (i for i in range(3) if self.bases_ammo[i] > 0),
            key=lambda i: abs(BASE_X[i] - cursor_x),
            default=None,
        )
        if best is None:
            return
        self.bases_ammo[best] -= 1
        bx = float(BASE_X[best])
        by = float(CITY_ROW - 1)
        self.player_missiles.append(
            PlayerMissile(x=bx, y=by, tx=float(cursor_x), ty=float(cursor_y))
        )

    def tick(self) -> None:
        if self.game_over:
            return

        # Game-over takes priority over wave-clear countdown
        if not any(self.cities):
            self.game_over = True
            return

        # Wave-clear countdown
        if self.wave_clear:
            self._wave_clear_ticks -= 1
            if self._wave_clear_ticks <= 0:
                self.wave_clear = False
                self.wave += 1
                for i in range(3):
                    self.bases_ammo[i] = min(BASE_AMMO, self.bases_ammo[i] + 5)
                self._spawn_wave()
            return

        # Move enemy missiles
        for m in self.enemy_missiles:
            if not m.alive:
                continue
            m.step()
            if m.at_target():
                m.alive = False
                self._ground_impact(m.tx)

        # Move player missiles
        for m in self.player_missiles:
            if not m.alive:
                continue
            m.step()
            if not m.alive:
                self.explosions.append(Explosion(m.tx, m.ty))

        # Update explosions + collision
        for exp in self.explosions:
            exp.step()
            for m in self.enemy_missiles:
                if m.alive and exp.contains(m.x, m.y):
                    m.alive = False
                    self.score += 25

        # Prune finished entities
        self.explosions = [e for e in self.explosions if not e.done]
        self.enemy_missiles = [m for m in self.enemy_missiles if m.alive]
        self.player_missiles = [m for m in self.player_missiles if m.alive]

        # Check wave clear
        if not self.enemy_missiles:
            self.wave_clear = True
            self._wave_clear_ticks = 30  # 3 s at 10 fps

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _spawn_wave(self) -> None:
        count = 4 + self.wave * 2
        speed = min(0.12 + self.wave * 0.02, 0.45)
        for _ in range(count):
            ox = random.uniform(3.0, GAME_W - 3.0)
            targets: List[float] = [
                float(CITY_X[i]) for i, alive in enumerate(self.cities) if alive
            ]
            targets += [float(bx) for bx in BASE_X]
            tx = random.choice(targets)
            self.enemy_missiles.append(
                EnemyMissile(ox=ox, x=ox, y=0.0, tx=tx, ty=float(CITY_ROW), speed=speed)
            )

    def _ground_impact(self, x: float) -> None:
        for i, (alive, cx) in enumerate(zip(self.cities, CITY_X)):
            if alive and abs(cx - x) <= 2:
                self.cities[i] = False
                self.explosions.append(Explosion(x, float(CITY_ROW)))
                return
        for i, bx in enumerate(BASE_X):
            if abs(bx - x) <= 2 and self.bases_ammo[i] > 0:
                self.bases_ammo[i] = 0
                self.explosions.append(Explosion(x, float(CITY_ROW)))
                return
        self.explosions.append(Explosion(x, float(CITY_ROW)))


# ---------------------------------------------------------------------------
# Textual screen
# ---------------------------------------------------------------------------


class BrokkDefenseScreen(ModalScreen[None]):
    """Full-screen BROKK DEFENSE easter egg.

    Controls:  ↑↓←→ to aim · Space/Enter to fire · Esc to quit
    Activate:  type  !-!-!  while the context modal is open
    """

    BINDINGS = [
        Binding("escape", "quit_game", "Quit", show=False),
    ]

    DEFAULT_CSS = """
    BrokkDefenseScreen {
        background: $surface;
        align: center middle;
        padding: 0;
    }
    #mc-canvas {
        background: black;
        color: white;
        width: auto;
        height: auto;
    }
    """

    def __init__(self) -> None:
        super().__init__()
        self._state = BrokkDefenseState()
        self._cursor_x: int = GAME_W // 2
        self._cursor_y: int = GAME_H // 3
        self._tick_timer = None
        self._mounted = False

    def compose(self) -> ComposeResult:
        yield Static("", id="mc-canvas", markup=False)

    def on_mount(self) -> None:
        self._mounted = True
        self._tick_timer = self.set_interval(0.1, self._on_tick)
        self._redraw()

    def _on_tick(self) -> None:
        self._state.tick()
        self._redraw()

    def _redraw(self) -> None:
        if not self._mounted:
            return
        self.query_one("#mc-canvas", Static).update(self._build_frame())

    # ------------------------------------------------------------------
    # Rendering
    # ------------------------------------------------------------------

    def _build_frame(self) -> Text:
        state = self._state
        w, h = GAME_W, GAME_H

        # 2-D grid: each cell is (char, style_str)
        grid: List[List[Tuple[str, str]]] = [[(" ", "") for _ in range(w)] for _ in range(h)]

        def put(x: int, y: int, ch: str, st: str) -> None:
            if 0 <= x < w and 0 <= y < h:
                grid[y][x] = (ch, st)

        # Ground line
        for x in range(w):
            put(x, CITY_ROW, "─", "bright_black")

        # Cities
        for i, (alive, cx) in enumerate(zip(state.cities, CITY_X)):
            put(cx, CITY_ROW, "▲" if alive else "☠", "bright_green" if alive else "bright_black")

        # Missile bases
        for i, bx in enumerate(BASE_X):
            if state.bases_ammo[i] > 0:
                put(bx, CITY_ROW, "Δ", "bright_cyan")
            else:
                put(bx, CITY_ROW, "_", "bright_black")

        # Enemy missile trails (Bresenham from origin → current position)
        for m in state.enemy_missiles:
            x0, y0 = int(round(m.ox)), 0
            x1, y1 = int(round(m.x)), int(round(m.y))
            dx_total = x1 - x0
            dy_total = y1 - y0
            trail_ch = "|"
            if abs(dx_total) > 0 and abs(dy_total) > 0:
                trail_ch = "\\" if (dx_total > 0) == (dy_total > 0) else "/"
            elif abs(dx_total) > 0:
                trail_ch = "─"
            for px, py in _bresenham(x0, y0, x1, y1):
                put(px, py, trail_ch, "red")
            put(x1, y1, "●", "bright_red")

        # Player missile trails
        for m in state.player_missiles:
            for tx, ty in m.trail:
                put(tx, ty, "·", "yellow")
            put(int(round(m.x)), int(round(m.y)), "*", "bright_yellow")

        # Explosions (ring of characters at radius)
        for exp in state.explosions:
            r = exp.radius
            ir = int(r) + 1
            style = "bright_red" if exp.expanding else "yellow"
            ch = "░" if exp.expanding else "▒"
            for dy in range(-ir, ir + 1):
                for dx in range(-ir, ir + 1):
                    if abs(math.hypot(dx, dy) - r) < 1.1:
                        put(int(exp.x) + dx, int(exp.y) + dy, ch, style)
            put(int(exp.x), int(exp.y), "✦", "bright_white")

        # Crosshair
        cx, cy = self._cursor_x, self._cursor_y
        put(cx - 1, cy, "─", "white")
        put(cx + 1, cy, "─", "white")
        put(cx, cy - 1, "│", "white")
        put(cx, cy + 1, "│", "white")
        put(cx, cy, "+", "bright_white")

        # ------- Assemble Rich Text -------
        out = Text()

        # Header
        header = f"  ★ BROKK DEFENSE ★    Score: {state.score:07d}    Wave: {state.wave}  "
        out.append(header.ljust(w), style="bold white on dark_blue")
        out.append("\n")

        # Game rows
        for row in grid:
            for ch, st in row:
                out.append(ch, style=st) if st else out.append(ch)
            out.append("\n")

        # Footer
        if state.game_over:
            footer = "  ★ GAME OVER ★  Your cities are dust.  Press Esc to exit  "
            out.append(footer.center(w), style="bold bright_red on black")
        elif state.wave_clear:
            next_wave = state.wave + 1
            footer = f"  ★ WAVE {state.wave} CLEAR! ★  Preparing wave {next_wave}...  "
            out.append(footer.center(w), style="bold bright_green on black")
        else:
            ammo_parts = []
            for i in range(3):
                ammo = state.bases_ammo[i]
                bar = "▪" * ammo + "·" * (BASE_AMMO - ammo)
                ammo_parts.append(f"[{i + 1}]{bar}")
            footer = "  ".join(ammo_parts) + "   ↑↓←→ Aim  Space Fire  Esc Quit"
            out.append(footer[:w].ljust(w), style="cyan")

        return out

    # ------------------------------------------------------------------
    # Input handling
    # ------------------------------------------------------------------

    def on_key(self, event: events.Key) -> None:
        key = event.key
        if self._state.game_over:
            return
        if key == "up":
            self._cursor_y = max(1, self._cursor_y - CURSOR_STEP)
        elif key == "down":
            self._cursor_y = min(GAME_H - 2, self._cursor_y + CURSOR_STEP)
        elif key == "left":
            self._cursor_x = max(1, self._cursor_x - CURSOR_STEP)
        elif key == "right":
            self._cursor_x = min(GAME_W - 2, self._cursor_x + CURSOR_STEP)
        elif key in ("space", "enter"):
            self._state.fire(self._cursor_x, self._cursor_y)
        self._redraw()

    def action_quit_game(self) -> None:
        if self._tick_timer is not None:
            self._tick_timer.stop()
            self._tick_timer = None
        self.dismiss(None)


# ---------------------------------------------------------------------------
# Bresenham line helper
# ---------------------------------------------------------------------------


def _bresenham(x0: int, y0: int, x1: int, y1: int) -> List[Tuple[int, int]]:
    """Return integer (x, y) points on the line from (x0, y0) to (x1, y1)."""
    points: List[Tuple[int, int]] = []
    dx = abs(x1 - x0)
    dy = abs(y1 - y0)
    sx = 1 if x0 < x1 else -1
    sy = 1 if y0 < y1 else -1
    err = dx - dy
    while True:
        points.append((x0, y0))
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 > -dy:
            err -= dy
            x0 += sx
        if e2 < dx:
            err += dx
            y0 += sy
    return points
