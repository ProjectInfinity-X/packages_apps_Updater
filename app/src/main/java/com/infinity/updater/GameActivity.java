/*
 * Copyright (C) 2026 Project Infinity X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infinity.updater;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

public class GameActivity extends Activity {

    private SlitherView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gameView = new SlitherView(this);
        setContentView(gameView);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override protected void onPause()  { super.onPause();  gameView.pause();  }
    @Override protected void onResume() { super.onResume(); gameView.resume(); }

    // =========================================================================
    class SlitherView extends View {

        private final Handler handler = new Handler();
        private Runnable runnable;

        private Paint paint, textPaint, bgPaint, gridPaint, uiPaint;
        private final Random random = new Random();

        // World
        private static final int WORLD_W = 4000;
        private static final int WORLD_H = 4000;
        private static final int FOOD_TARGET = 320;
        private static final int AI_COUNT = 8;
        private static final int MAX_INF_FOODS = 6;

        private float cameraX = 0, cameraY = 0;
        private int screenWidth, screenHeight;

        // Entities
        private Snake player;
        private final ArrayList<Snake>        aiSnakes      = new ArrayList<>();
        private final ArrayList<Food>         foods         = new ArrayList<>();
        private final ArrayList<FloatingText> floatingTexts = new ArrayList<>();

        // State
        private boolean isGameRunning = false;
        private boolean isGameOver    = false;
        private boolean isPaused      = false;
        private int     highScore     = 0;

        // Touch / steering
        private float   touchTargetX, touchTargetY;
        private boolean isTouching = false;
        private boolean isBoosting = false;
        
        // UI Bounds
        private RectF pauseBtnRect = new RectF();

        // Theme
        private boolean isDarkMode;

        // Infinity food animation tick
        private long infTick = 0;

        // Prefs
        private SharedPreferences prefs;

        private static final int[][] AI_COLORS = {
            {0xFFFF4455, 0xFFCC1122}, {0xFF44FF88, 0xFF00CC44},
            {0xFFFF9900, 0xFFCC6600}, {0xFFCC44FF, 0xFF880099},
            {0xFFFFFF33, 0xFFCCBB00}, {0xFFFF44CC, 0xFFCC0088},
            {0xFF33FFFF, 0xFF0099CC}, {0xFFFF7744, 0xFFCC4400}
        };

        // ─────────────────────────────────────────────────────────────────────
        SlitherView(Context context) {
            super(context);
            prefs = context.getSharedPreferences("InfXSlither", Context.MODE_PRIVATE);
            highScore = prefs.getInt("hs", 0);

            paint     = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextAlign(Paint.Align.CENTER);
            bgPaint   = new Paint();
            gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(1.5f);
            uiPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
            uiPaint.setTextAlign(Paint.Align.CENTER);

            runnable = new Runnable() {
                @Override public void run() {
                    if (isGameRunning && !isPaused) { update(); }
                    invalidate();
                    handler.postDelayed(this, 16);
                }
            };
        }

        // ─────────────────────────────────────────────────────────────────────
        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            screenWidth  = w;
            screenHeight = h;
            int nightMode = getContext().getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            isDarkMode = nightMode == Configuration.UI_MODE_NIGHT_YES;
            initGame();
        }

        // ─────────────────────────────────────────────────────────────────────
        private void initGame() {
            foods.clear();
            aiSnakes.clear();
            floatingTexts.clear();
            isGameOver = false;
            isPaused   = false;

            float cx = WORLD_W / 2f, cy = WORLD_H / 2f;
            player = new Snake(cx, cy, 0xFF00E5FF, 0xFF0077AA, true);
            touchTargetX = screenWidth  / 2f;
            touchTargetY = screenHeight / 2f;

            for (int i = 0; i < AI_COUNT; i++) spawnAISnake(i);

            int infCount = 0;
            for (int i = 0; i < FOOD_TARGET; i++) {
                int type = Food.TYPE_NORMAL;
                if (infCount < MAX_INF_FOODS && random.nextInt(100) < 5) {
                    type = Food.TYPE_INF;
                    infCount++;
                }
                spawnFood(false, type);
            }
        }

        private void spawnAISnake(int index) {
            float x = 300 + random.nextFloat() * (WORLD_W - 600);
            float y = 300 + random.nextFloat() * (WORLD_H - 600);
            int i = index % AI_COLORS.length;
            Snake ai = new Snake(x, y, AI_COLORS[i][0], AI_COLORS[i][1], false);
            
            ai.aiType = random.nextInt(5);
            switch (ai.aiType) {
                case 0: 
                    ai.targetLength = 20 + random.nextInt(30);
                    ai.baseSpeed = 5.5f;
                    break;
                case 1: 
                    ai.targetLength = 15 + random.nextInt(20);
                    ai.baseSpeed = 8.5f;
                    break;
                case 2: 
                    ai.targetLength = 40 + random.nextInt(50);
                    ai.baseSpeed = 7.0f;
                    break;
                case 3: 
                    ai.targetLength = 80 + random.nextInt(40);
                    ai.baseSpeed = 5.0f;
                    ai.turnSpeed = 0.08f;
                    break;
                case 4: 
                    ai.targetLength = 150 + random.nextInt(100);
                    ai.baseSpeed = 6.5f;
                    ai.turnSpeed = 0.02f;
                    break;
            }
            
            ai.targetX = x;
            ai.targetY = y;
            aiSnakes.add(ai);
        }

        private void respawnAI(int index) {
            if (aiSnakes.size() < AI_COUNT) spawnAISnake(index % AI_COUNT);
        }

        private void spawnFood(boolean burst, int type) {
            float x, y;
            if (burst && player != null) {
                x = player.head().x + (random.nextFloat() - 0.5f) * 500f;
                y = player.head().y + (random.nextFloat() - 0.5f) * 500f;
            } else {
                x = 80 + random.nextFloat() * (WORLD_W - 160);
                y = 80 + random.nextFloat() * (WORLD_H - 160);
            }
            x = Math.max(60, Math.min(WORLD_W - 60, x));
            y = Math.max(60, Math.min(WORLD_H - 60, y));
            foods.add(new Food(x, y, type));
        }

        private int countFoodsOfType(int type) {
            int n = 0;
            for (Food f : foods) if (f.type == type) n++;
            return n;
        }

        // ─────────────────────────────────────────────────────────────────────
        //  UPDATE
        // ─────────────────────────────────────────────────────────────────────
        private void update() {
            if (!isGameRunning || isGameOver || isPaused) return;
            infTick++;

            player.isBoosting = isBoosting;

            if (isTouching) {
                float worldTX = cameraX + touchTargetX;
                float worldTY = cameraY + touchTargetY;
                player.steerToward(worldTX, worldTY);
            }
            player.update();

            cameraX += (player.head().x - screenWidth  / 2f - cameraX) * 0.08f;
            cameraY += (player.head().y - screenHeight / 2f - cameraY) * 0.08f;
            cameraX = Math.max(0, Math.min(WORLD_W - screenWidth,  cameraX));
            cameraY = Math.max(0, Math.min(WORLD_H - screenHeight, cameraY));

            for (Food f : foods) {
                if (f.type == Food.TYPE_GOLDEN) {
                    if (player != null) {
                        float pdx = f.x - player.head().x;
                        float pdy = f.y - player.head().y;
                        float dist = (float) Math.sqrt(pdx * pdx + pdy * pdy);
                        
                        if (dist < 350f) {
                            f.vx += (pdx / dist) * 0.5f;
                            f.vy += (pdy / dist) * 0.5f;
                        } else {
                            float speed = (float) Math.hypot(f.vx, f.vy);
                            if (speed > 4f) {
                                f.vx *= 0.98f;
                                f.vy *= 0.98f;
                            }
                        }
                    }

                    float speed = (float) Math.hypot(f.vx, f.vy);
                    if (speed > 10f) {
                        f.vx = (f.vx / speed) * 10f;
                        f.vy = (f.vy / speed) * 10f;
                    }

                    f.x += f.vx;
                    f.y += f.vy;

                    if (f.x < 60) { f.x = 60; f.vx = -f.vx; }
                    if (f.x > WORLD_W - 60) { f.x = WORLD_W - 60; f.vx = -f.vx; }
                    if (f.y < 60) { f.y = 60; f.vy = -f.vy; }
                    if (f.y > WORLD_H - 60) { f.y = WORLD_H - 60; f.vy = -f.vy; }
                }
            }

            for (Snake ai : aiSnakes) {
                ai.updateAI(player, foods, aiSnakes);
                ai.update();
            }

            checkPlayerEatsFood();
            checkAIEatsFood();
            checkPlayerVsAI();
            checkAIVsAI();

            Iterator<FloatingText> ft = floatingTexts.iterator();
            while (ft.hasNext()) {
                FloatingText t = ft.next();
                t.worldY -= 2.2f;
                t.alpha  -= 2;
                if (t.alpha <= 0) ft.remove();
            }

            int infAlive = countFoodsOfType(Food.TYPE_INF);
            int goldAlive = countFoodsOfType(Food.TYPE_GOLDEN);

            if (infAlive < MAX_INF_FOODS && random.nextInt(150) < 1) {
                spawnFood(false, Food.TYPE_INF);
            }
            if (goldAlive < 1 && random.nextInt(450) < 1) {
                spawnFood(false, Food.TYPE_GOLDEN);
            }

            while (foods.size() < FOOD_TARGET) {
                spawnFood(false, Food.TYPE_NORMAL);
            }
        }

        private void checkPlayerEatsFood() {
            Iterator<Food> it = foods.iterator();
            while (it.hasNext()) {
                Food f = it.next();
                if (dist(player.head(), f.x, f.y) < player.radius + f.radius + 4f) {
                    it.remove();
                    
                    if (f.type == Food.TYPE_GOLDEN) {
                        player.targetLength += 54; 
                        floatingTexts.add(new FloatingText("Golden Infinity-X Boost!", 
                            player.head().x, player.head().y - 60f, 0xFFFFD700, 42f, 310));
                    } else if (f.type == Food.TYPE_INF) {
                        player.targetLength += 18;
                        floatingTexts.add(new FloatingText("∞ Infinity-X Consumed!",
                            player.head().x, player.head().y - 60f, 0xFF00AAFF, 36f, 310));
                    } else if (f.type == Food.TYPE_DROPPED) {
                        player.targetLength += 3; 
                    } else {
                        player.targetLength += 1;
                    }
                    
                    int sc = playerScore();
                    if (sc > highScore) {
                        highScore = sc;
                        prefs.edit().putInt("hs", highScore).apply();
                    }
                }
            }
        }

        private void checkAIEatsFood() {
            for (Snake ai : aiSnakes) {
                Iterator<Food> it = foods.iterator();
                while (it.hasNext()) {
                    Food f = it.next();
                    if (dist(ai.head(), f.x, f.y) < ai.radius + f.radius + 2f) {
                        it.remove();
                        if (f.type == Food.TYPE_GOLDEN) ai.targetLength += 54;
                        else if (f.type == Food.TYPE_INF) ai.targetLength += 18;
                        else if (f.type == Food.TYPE_DROPPED) ai.targetLength += 3;
                        else ai.targetLength += 1;
                    }
                }
            }
        }

        private void checkPlayerVsAI() {
            for (int a = aiSnakes.size() - 1; a >= 0; a--) {
                Snake ai = aiSnakes.get(a);

                for (int i = 4; i < ai.body.size(); i++) {
                    PointF seg = ai.body.get(i);
                    if (dist(player.head(), seg.x, seg.y) < player.radius + ai.radiusAt(i)) {
                        gameOver();
                        return;
                    }
                }

                for (int i = 4; i < player.body.size(); i++) {
                    PointF seg = player.body.get(i);
                    if (dist(ai.head(), seg.x, seg.y) < ai.radius + player.radiusAt(i)) {
                        killSnake(ai, a);
                        break;
                    }
                }
            }
        }

        private void checkAIVsAI() {
            outer:
            for (int a = aiSnakes.size() - 1; a >= 0; a--) {
                Snake killer = aiSnakes.get(a);
                for (int b = 0; b < aiSnakes.size(); b++) {
                    if (a == b) continue;
                    Snake victim = aiSnakes.get(b);
                    for (int i = 4; i < victim.body.size(); i++) {
                        PointF seg = victim.body.get(i);
                        if (dist(killer.head(), seg.x, seg.y) < killer.radius + victim.radiusAt(i)) {
                            killSnake(killer, a);
                            continue outer;
                        }
                    }
                }
            }
        }

        private void killSnake(Snake dead, int idx) {
            int drop = Math.min(dead.body.size() / 3, 100);
            for (int i = 0; i < drop; i++) {
                float x = dead.body.get(i * 3 % dead.body.size()).x + (random.nextFloat() - 0.5f) * 40f;
                float y = dead.body.get(i * 3 % dead.body.size()).y + (random.nextFloat() - 0.5f) * 40f;
                Food dropFood = new Food(x, y, Food.TYPE_DROPPED);
                dropFood.color = dead.bodyColor; 
                foods.add(dropFood);
            }
            
            if (idx != -1) {
                aiSnakes.remove(idx);
                final int ri = idx;
                handler.postDelayed(new Runnable() {
                    @Override public void run() { respawnAI(ri); }
                }, 4000);
            }
        }

        private void gameOver() {
            isGameOver    = true;
            isGameRunning = false;
        }

        private int playerScore() { return Math.max(0, player.body.size() - 10); }

        private float dist(PointF p, float x, float y) {
            float dx = p.x - x, dy = p.y - y;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        // ─────────────────────────────────────────────────────────────────────
        //  DRAW
        // ─────────────────────────────────────────────────────────────────────
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (screenWidth == 0) return;

            drawBackground(canvas);

            if (!isGameRunning && !isGameOver) {
                drawSplash(canvas);
                return;
            }

            canvas.save();
            canvas.translate(-cameraX, -cameraY);

            drawGrid(canvas);
            drawWorldBorder(canvas);
            drawFood(canvas);
            for (Snake ai : aiSnakes) drawSnake(canvas, ai);
            if (player != null) drawSnake(canvas, player);
            drawFloatingTexts(canvas);

            canvas.restore();

            drawHUD(canvas);
            if (isGameRunning && !isGameOver) {
                drawPauseButton(canvas);
            }
            if (isPaused) {
                drawPausedOverlay(canvas);
            }
            if (isGameOver) drawGameOver(canvas);
        }

        private void drawBackground(Canvas canvas) {
            if (isDarkMode) {
                canvas.drawColor(0xFF050510);
            } else {
                bgPaint.setShader(new LinearGradient(0, 0, 0, screenHeight,
                    0xFFEEF2FF, 0xFFCDD8FF, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, screenWidth, screenHeight, bgPaint);
                bgPaint.setShader(null);
            }
        }

        private void drawGrid(Canvas canvas) {
            gridPaint.setColor(isDarkMode ? 0x12AAAAFF : 0x15000055);
            float ox = -(cameraX % 100), oy = -(cameraY % 100);
            for (float x = ox - 100 + cameraX; x < cameraX + screenWidth  + 100; x += 100)
                canvas.drawLine(x, cameraY, x, cameraY + screenHeight, gridPaint);
            for (float y = oy - 100 + cameraY; y < cameraY + screenHeight + 100; y += 100)
                canvas.drawLine(cameraX, y, cameraX + screenWidth, y, gridPaint);
        }

        private void drawWorldBorder(Canvas canvas) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(10f);
            paint.setColor(0xFF3366FF);
            canvas.drawRect(30, 30, WORLD_W - 30, WORLD_H - 30, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawFood(Canvas canvas) {
            Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (Food f : foods) {
                if (f.x < cameraX - 60 || f.x > cameraX + screenWidth  + 60) continue;
                if (f.y < cameraY - 60 || f.y > cameraY + screenHeight + 60) continue;

                if (f.type == Food.TYPE_INF || f.type == Food.TYPE_GOLDEN) {
                    float pulse = 1f + 0.2f * (float) Math.sin(infTick * 0.07);
                    float gr = f.radius * 3.5f * pulse;
                    int outerColor = f.type == Food.TYPE_GOLDEN ? 0x99FFAA00 : 0x9900BBFF;
                    int textShadow = f.type == Food.TYPE_GOLDEN ? 0xFFFFAA00 : 0xFF0088FF;
                    
                    fp.setShader(new RadialGradient(f.x, f.y, gr, outerColor, 0x0000BBFF, Shader.TileMode.CLAMP));
                    canvas.drawCircle(f.x, f.y, gr, fp);
                    fp.setShader(null);

                    fp.setColor(f.color);
                    fp.setTextSize(f.radius * 3.2f * pulse);
                    fp.setTextAlign(Paint.Align.CENTER);
                    fp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                    fp.setShadowLayer(f.radius * 2f, 0, 0, textShadow);
                    canvas.drawText("∞", f.x, f.y + f.radius * 1.1f, fp);
                    fp.clearShadowLayer();
                } else if (f.type == Food.TYPE_DROPPED) {
                    fp.setShader(new RadialGradient(f.x, f.y, f.radius * 2.5f,
                        brighten(f.color, 50), f.color & 0x00FFFFFF, Shader.TileMode.CLAMP));
                    canvas.drawCircle(f.x, f.y, f.radius * 2.5f, fp);
                    fp.setShader(null);
                    fp.setColor(f.color);
                    canvas.drawCircle(f.x, f.y, f.radius * 1.2f, fp);
                } else {
                    fp.setShader(new RadialGradient(f.x, f.y, f.radius * 2f,
                        f.color, f.color & 0x00FFFFFF, Shader.TileMode.CLAMP));
                    canvas.drawCircle(f.x, f.y, f.radius * 1.8f, fp);
                    fp.setShader(null);
                    fp.setColor(f.color);
                    canvas.drawCircle(f.x, f.y, f.radius, fp);
                    fp.setColor(0xBBFFFFFF);
                    canvas.drawCircle(f.x - f.radius * 0.3f, f.y - f.radius * 0.3f, f.radius * 0.32f, fp);
                }
            }
        }

        private void drawSnake(Canvas canvas, Snake s) {
            if (s.body.isEmpty()) return;
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            int sz = s.body.size();

            for (int i = sz - 1; i >= 0; i--) {
                PointF p = s.body.get(i);
                if (p.x < cameraX - 80 || p.x > cameraX + screenWidth  + 80) continue;
                if (p.y < cameraY - 80 || p.y > cameraY + screenHeight + 80) continue;

                float r   = s.radiusAt(i);
                float t   = 1f - (float) i / sz;
                int   col = lerpColor(s.bodyColor, s.headColor, t);

                if (s.isPlayer) {
                    sp.setShader(new RadialGradient(p.x, p.y, r * 1.8f,
                        adjustAlpha(col, 55), 0x00000000, Shader.TileMode.CLAMP));
                    canvas.drawCircle(p.x, p.y, r * 1.8f, sp);
                    sp.setShader(null);
                }

                sp.setShader(new RadialGradient(
                    p.x - r * 0.3f, p.y - r * 0.3f, r * 1.2f,
                    brighten(col, 55), col, Shader.TileMode.CLAMP));
                canvas.drawCircle(p.x, p.y, r, sp);
                sp.setShader(null);

                if (i % 3 == 0 && r > 9f) {
                    sp.setColor(adjustAlpha(darken(col, 45), 110));
                    canvas.drawCircle(p.x + r * 0.22f, p.y + r * 0.22f, r * 0.26f, sp);
                }
            }

            // Head
            PointF head = s.body.get(0);
            float hr = s.radius;
            sp.setShader(new RadialGradient(
                head.x - hr * 0.3f, head.y - hr * 0.3f, hr * 1.4f,
                brighten(s.headColor, 70), s.headColor, Shader.TileMode.CLAMP));
            canvas.drawCircle(head.x, head.y, hr, sp);
            sp.setShader(null);

            // Eyes
            float ea = s.currentAngle;
            float ex = head.x + (float) Math.cos(ea) * hr * 0.45f;
            float ey = head.y + (float) Math.sin(ea) * hr * 0.45f;
            float px = -(float) Math.sin(ea) * hr * 0.42f;
            float py  =  (float) Math.cos(ea) * hr * 0.42f;

            sp.setColor(Color.WHITE);
            canvas.drawCircle(ex + px, ey + py, hr * 0.30f, sp);
            canvas.drawCircle(ex - px, ey - py, hr * 0.30f, sp);
            sp.setColor(Color.BLACK);
            float ps = hr * 0.09f;
            canvas.drawCircle(ex + px + ps * (float) Math.cos(ea), ey + py + ps * (float) Math.sin(ea), hr * 0.16f, sp);
            canvas.drawCircle(ex - px + ps * (float) Math.cos(ea), ey - py + ps * (float) Math.sin(ea), hr * 0.16f, sp);

            if (s.isPlayer) {
                sp.setColor(isDarkMode ? 0xDDFFFFFF : 0xDD111111);
                sp.setTextSize(26f);
                sp.setTextAlign(Paint.Align.CENTER);
                sp.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                canvas.drawText("YOU", head.x, head.y - hr - 10f, sp);
            }
        }

        private void drawFloatingTexts(Canvas canvas) {
            for (FloatingText ft : floatingTexts) {
                textPaint.setColor(adjustAlpha(ft.color, ft.alpha));
                textPaint.setTextSize(ft.size);
                textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                textPaint.setShadowLayer(18f, 0, 0, adjustAlpha(ft.color, ft.alpha / 2));
                canvas.drawText(ft.text, ft.worldX, ft.worldY, textPaint);
                textPaint.clearShadowLayer();
            }
        }

        private void drawHUD(Canvas canvas) {
            uiPaint.setTextAlign(Paint.Align.LEFT);
            uiPaint.setColor(isDarkMode ? 0xBB000022 : 0xBBFFFFFF);
            canvas.drawRoundRect(new RectF(16, 16, 320, 148), 20, 20, uiPaint);

            uiPaint.setColor(0xFF00AAFF);
            uiPaint.setTextSize(30f);
            uiPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            canvas.drawText("∞  INFINITY-X", 32, 58, uiPaint);

            uiPaint.setColor(isDarkMode ? Color.WHITE : 0xFF111111);
            uiPaint.setTextSize(28f);
            canvas.drawText("Score: " + playerScore(), 32, 98, uiPaint);
            uiPaint.setTextSize(24f);
            canvas.drawText("Best:  " + highScore, 32, 136, uiPaint);
        }

        private void drawPauseButton(Canvas canvas) {
            float size = 80f;
            float padding = 20f;
            pauseBtnRect.set(screenWidth - size - padding, padding, screenWidth - padding, padding + size);

            uiPaint.setColor(isDarkMode ? 0x88000022 : 0x88FFFFFF);
            canvas.drawRoundRect(pauseBtnRect, 15f, 15f, uiPaint);

            uiPaint.setColor(0xFF00AAFF);
            uiPaint.setTextAlign(Paint.Align.CENTER);
            uiPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            uiPaint.setTextSize(40f);
            
            String icon = isPaused ? "▶" : "⏸";
            drawCenteredTextY(canvas, icon, pauseBtnRect.centerX(), pauseBtnRect.top, pauseBtnRect.bottom, uiPaint);
        }

        private void drawPausedOverlay(Canvas canvas) {
            uiPaint.setColor(0x99000000);
            canvas.drawRect(0, 0, screenWidth, screenHeight, uiPaint);

            textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            textPaint.setTextSize(96f);
            textPaint.setColor(Color.WHITE);
            canvas.drawText("PAUSED", screenWidth / 2f, screenHeight / 2f - 20f, textPaint);

            textPaint.setTextSize(36f);
            textPaint.setColor(0xFF00AAFF);
            canvas.drawText("Tap top-right to resume", screenWidth / 2f, screenHeight / 2f + 50f, textPaint);
        }

        private void drawCenteredTextY(Canvas c, String t, float cx, float topY, float bottomY, Paint p) {
            float centerY = topY + (bottomY - topY) / 2f;
            float exactY = centerY - ((p.descent() + p.ascent()) / 2f);
            c.drawText(t, cx, exactY, p);
        }

        private void drawSplash(Canvas canvas) {
            textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            textPaint.setTextSize(200f);
            textPaint.setColor(0xFF00AAFF);
            textPaint.setShadowLayer(80f, 0, 0, 0xFF0055FF);
            canvas.drawText("∞", screenWidth / 2f, screenHeight * 0.28f, textPaint);
            textPaint.clearShadowLayer();

            textPaint.setTextSize(78f);
            textPaint.setColor(isDarkMode ? Color.WHITE : 0xFF111111);
            canvas.drawText("INFINITY X", screenWidth / 2f, screenHeight * 0.40f, textPaint);

            textPaint.setTextSize(40f);
            textPaint.setColor(0xFF00AAFF);
            canvas.drawText("S L I T H E R", screenWidth / 2f, screenHeight * 0.48f, textPaint);

            // Added Developer Credit Line
            textPaint.setTextSize(22f);
            textPaint.setColor(0xFF0077CC);
            textPaint.setUnderlineText(true);
            canvas.drawText("Developed by tejas101k", screenWidth / 2f, screenHeight * 0.52f, textPaint);
            textPaint.setUnderlineText(false);

            textPaint.setTextSize(30f);
            textPaint.setColor(isDarkMode ? 0xBBFFFFFF : 0xBB333333);
            canvas.drawText("Drag to steer your snake", screenWidth / 2f, screenHeight * 0.58f, textPaint);
            canvas.drawText("Hold 2nd finger to BOOST (costs length!)", screenWidth / 2f, screenHeight * 0.64f, textPaint);
            canvas.drawText("Eat ∞ symbols for mega growth!", screenWidth / 2f, screenHeight * 0.70f, textPaint);
            canvas.drawText("Avoid hitting other snakes!", screenWidth / 2f, screenHeight * 0.76f, textPaint);

            uiPaint.setColor(0xFF0077CC);
            float btnTop = screenHeight * 0.82f;
            float btnBottom = screenHeight * 0.90f;
            canvas.drawRoundRect(new RectF(screenWidth * 0.25f, btnTop,
                screenWidth * 0.75f, btnBottom), 50f, 50f, uiPaint);
            textPaint.setTextSize(50f);
            textPaint.setColor(Color.WHITE);
            drawCenteredTextY(canvas, "TAP TO PLAY", screenWidth / 2f, btnTop, btnBottom, textPaint);

            if (highScore > 0) {
                textPaint.setTextSize(30f);
                textPaint.setColor(0xFF00AAFF);
                canvas.drawText("Best: " + highScore, screenWidth / 2f, screenHeight * 0.96f, textPaint);
            }
        }

        private void drawGameOver(Canvas canvas) {
            uiPaint.setColor(0xCC000000);
            canvas.drawRect(0, 0, screenWidth, screenHeight, uiPaint);

            textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            textPaint.setTextSize(96f);
            textPaint.setColor(0xFFFF4466);
            textPaint.setShadowLayer(30f, 0, 0, 0xFFFF0033);
            canvas.drawText("GAME OVER", screenWidth / 2f, screenHeight * 0.38f, textPaint);
            textPaint.clearShadowLayer();

            textPaint.setTextSize(52f);
            textPaint.setColor(Color.WHITE);
            canvas.drawText("Score: "      + playerScore(), screenWidth / 2f, screenHeight * 0.50f, textPaint);
            canvas.drawText("Best Score: " + highScore,     screenWidth / 2f, screenHeight * 0.58f, textPaint);

            uiPaint.setColor(0xFF0077CC);
            float btnTop = screenHeight * 0.68f;
            float btnBottom = screenHeight * 0.78f;
            canvas.drawRoundRect(new RectF(screenWidth * 0.25f, btnTop,
                screenWidth * 0.75f, btnBottom), 50f, 50f, uiPaint);
            textPaint.setTextSize(50f);
            drawCenteredTextY(canvas, "PLAY AGAIN", screenWidth / 2f, btnTop, btnBottom, textPaint);
        }

        // ─────────────────────────────────────────────────────────────────────
        //  TOUCH
        // ─────────────────────────────────────────────────────────────────────
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!isGameRunning) {
                        initGame();
                        isGameRunning = true;
                        return true;
                    }

                    // Check if Pause button is tapped
                    if (isGameRunning && !isGameOver && pauseBtnRect.contains(event.getX(), event.getY())) {
                        isPaused = !isPaused;
                        isTouching = false;
                        isBoosting = false;
                        return true;
                    }

                    if (isPaused) return true;

                    touchTargetX = event.getX();
                    touchTargetY = event.getY();
                    isTouching   = true;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (isGameRunning && !isGameOver && !isPaused) isBoosting = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isGameRunning && !isGameOver && !isPaused) {
                        touchTargetX = event.getX(0);
                        touchTargetY = event.getY(0);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() <= 2) isBoosting = false;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isTouching = false;
                    isBoosting = false;
                    break;
            }
            return true;
        }

        public void resume() { handler.post(runnable); }
        public void pause()  { handler.removeCallbacks(runnable); }

        // ─────────────────────────────────────────────────────────────────────
        //  Colour helpers
        // ─────────────────────────────────────────────────────────────────────
        private int lerpColor(int a, int b, float t) {
            int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
            return 0xFF000000
                | ((int)(ar + (br - ar) * t) << 16)
                | ((int)(ag + (bg - ag) * t) <<  8)
                |  (int)(ab + (bb - ab) * t);
        }
        private int brighten(int c, int amt) {
            return 0xFF000000
                | (Math.min(255, ((c >> 16) & 0xFF) + amt) << 16)
                | (Math.min(255, ((c >>  8) & 0xFF) + amt) <<  8)
                |  Math.min(255, (c & 0xFF) + amt);
        }
        private int darken(int c, int amt) {
            return 0xFF000000
                | (Math.max(0, ((c >> 16) & 0xFF) - amt) << 16)
                | (Math.max(0, ((c >>  8) & 0xFF) - amt) <<  8)
                |  Math.max(0, (c & 0xFF) - amt);
        }
        private int adjustAlpha(int c, int a) {
            return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
        }

        // =====================================================================
        //  Snake
        // =====================================================================
        class Snake {
            ArrayList<PointF> body = new ArrayList<>();
            int   headColor, bodyColor;
            float radius        = 20f;
            float baseSpeed     = 7.5f;
            float turnSpeed     = 0.13f;
            int   targetLength  = 12;
            float targetX, targetY;
            boolean isPlayer;
            boolean isBoosting  = false;
            int aiType          = 0;

            float currentAngle = 0f;
            float targetAngle  = 0f;

            private long aiThinkAt = 0;
            private int boostDropTick = 0;

            Snake(float sx, float sy, int hColor, int bColor, boolean isPlayer) {
                this.headColor = hColor;
                this.bodyColor = bColor;
                this.isPlayer  = isPlayer;
                this.currentAngle = (float)(Math.random() * Math.PI * 2);
                this.targetAngle  = currentAngle;
                this.targetX = sx;
                this.targetY = sy;
                for (int i = 0; i < 12; i++)
                    body.add(new PointF(
                        sx - (float)Math.cos(currentAngle) * i * 14f,
                        sy - (float)Math.sin(currentAngle) * i * 14f
                    ));
            }

            PointF head() { return body.get(0); }

            float radiusAt(int i) {
                float t    = 1f - (float) i / Math.max(body.size(), 1);
                float base = Math.min(8f + body.size() * 0.19f, radius);
                return base * (0.5f + 0.5f * t);
            }

            void steerToward(float wx, float wy) {
                float dx = wx - head().x, dy = wy - head().y;
                if (Math.abs(dx) > 2f || Math.abs(dy) > 2f)
                    targetAngle = (float) Math.atan2(dy, dx);
            }

            void update() {
                float da = angleDiff(targetAngle, currentAngle);
                currentAngle += da * turnSpeed;

                float speed = isBoosting && body.size() > 15 ? baseSpeed * 1.8f : baseSpeed;

                float nx = head().x + (float) Math.cos(currentAngle) * speed;
                float ny = head().y + (float) Math.sin(currentAngle) * speed;

                if (nx < 30) {
                    nx = 30; currentAngle = (float)Math.PI - currentAngle; targetAngle = currentAngle;
                } else if (nx > WORLD_W - 30) {
                    nx = WORLD_W - 30; currentAngle = (float)Math.PI - currentAngle; targetAngle = currentAngle;
                }
                
                if (ny < 30) {
                    ny = 30; currentAngle = -currentAngle; targetAngle = currentAngle;
                } else if (ny > WORLD_H - 30) {
                    ny = WORLD_H - 30; currentAngle = -currentAngle; targetAngle = currentAngle;
                }

                body.add(0, new PointF(nx, ny));

                if (isBoosting && body.size() > 15) {
                    boostDropTick++;
                    if (boostDropTick > 8) {
                        targetLength--;
                        PointF tail = body.get(body.size() - 1);
                        Food f = new Food(tail.x, tail.y, Food.TYPE_DROPPED);
                        f.color = bodyColor;
                        foods.add(f);
                        boostDropTick = 0;
                    }
                }

                while (body.size() > targetLength) body.remove(body.size() - 1);
            }

            void updateAI(Snake player, ArrayList<Food> foods, ArrayList<Snake> allAI) {
                long now = System.currentTimeMillis();
                
                this.isBoosting = false;

                if (random.nextInt(250) < 2) {
                    float escapeAngle = currentAngle + (random.nextFloat() - 0.5f);
                    targetX = head().x + (float)Math.cos(escapeAngle) * 2000f;
                    targetY = head().y + (float)Math.sin(escapeAngle) * 2000f;
                    aiThinkAt = now + 1500; 
                    steerToward(targetX, targetY);
                    return;
                }

                if (aiType == 4) {
                    if (now > aiThinkAt) {
                        float dist = 2000f + random.nextFloat() * 2000f;
                        targetX = head().x + (float)Math.cos(currentAngle + (random.nextFloat() - 0.5f)*0.5f) * dist;
                        targetY = head().y + (float)Math.sin(currentAngle + (random.nextFloat() - 0.5f)*0.5f) * dist;
                        aiThinkAt = now + 4000 + random.nextInt(3000); 
                        steerToward(targetX, targetY);
                    }
                    return; 
                }

                int updateDelay = aiType == 0 ? 400 : 150; 
                if (now < aiThinkAt) return;
                aiThinkAt = now + updateDelay + random.nextInt(200);

                float hx = head().x, hy = head().y;
                float bestDist = Float.MAX_VALUE;
                float bx = hx + (float)Math.cos(currentAngle) * 500f; 
                float by = hy + (float)Math.sin(currentAngle) * 500f;

                boolean targetingSpecial = false;

                for (Food f : foods) {
                    if (f.type == Food.TYPE_NORMAL || f.type == Food.TYPE_DROPPED) continue;
                    float d = hypot(hx, hy, f.x, f.y);
                    if (d < bestDist) { 
                        bestDist = d; bx = f.x; by = f.y; 
                        targetingSpecial = true;
                    }
                }

                if (targetingSpecial && bestDist < 800f && body.size() > 20 && random.nextInt(10) < 5) {
                    this.isBoosting = true;
                }

                if (bestDist > 1000f && aiType != 0) {
                    bestDist = Float.MAX_VALUE;
                    for (Food f : foods) {
                        if (f.type == Food.TYPE_INF || f.type == Food.TYPE_GOLDEN) continue;
                        float d = hypot(hx, hy, f.x, f.y);
                        if (d < bestDist && (d < (aiType == 2 ? 800f : 400f))) { 
                            bestDist = d; bx = f.x; by = f.y; 
                        }
                    }
                }

                float distToPlayer = hypot(hx, hy, player.head().x, player.head().y);
                if (distToPlayer < 400f) {
                    bx = hx + (hx - player.head().x) * 2f;
                    by = hy + (hy - player.head().y) * 2f;
                    
                    if (distToPlayer < 250f && body.size() > 18) {
                        this.isBoosting = true;
                    }
                }

                float margin = 350f;
                if (hx < margin)           bx += 600f;
                if (hx > WORLD_W - margin) bx -= 600f;
                if (hy < margin)           by += 600f;
                if (hy > WORLD_H - margin) by -= 600f;

                for (Snake other : allAI) {
                    if (other == this) continue;
                    float d = hypot(hx, hy, other.head().x, other.head().y);
                    if (d < 150f) {
                        bx += (hx - other.head().x) * 2f;
                        by += (hy - other.head().y) * 2f;
                    }
                }

                for (int i = 0; i < player.body.size(); i += 4) {
                    PointF seg = player.body.get(i);
                    float d = hypot(hx, hy, seg.x, seg.y);
                    if (d < 160f) {
                        bx += (hx - seg.x) * 1.2f;
                        by += (hy - seg.y) * 1.2f;
                    }
                }

                targetX = bx;
                targetY = by;
                steerToward(targetX, targetY);
            }

            private float hypot(float ax, float ay, float bx, float by) {
                float dx = ax - bx, dy = ay - by;
                return (float) Math.sqrt(dx * dx + dy * dy);
            }

            private float angleDiff(float target, float current) {
                float d = target - current;
                while (d >  Math.PI) d -= (float)(2 * Math.PI);
                while (d < -Math.PI) d += (float)(2 * Math.PI);
                return d;
            }
        }

        // =====================================================================
        //  Food
        // =====================================================================
        class Food {
            public static final int TYPE_NORMAL = 0;
            public static final int TYPE_INF = 1;
            public static final int TYPE_GOLDEN = 2;
            public static final int TYPE_DROPPED = 3;

            float   x, y, radius, vx, vy;
            int     color, type;

            private final int[] PALETTE = {
                0xFFFF6B9D, 0xFF00E5FF, 0xFFFFF176, 0xFF69FF47,
                0xFFFF8A65, 0xFFCE93D8, 0xFF80DEEA, 0xFFFFD740
            };

            Food(float x, float y, int type) {
                this.x    = x;
                this.y    = y;
                this.type = type;
                
                if (type == TYPE_GOLDEN) {
                    this.radius = 26f;
                    this.color = 0xFFFFD700;
                    float randomAngle = (float)(Math.random() * Math.PI * 2);
                    this.vx = (float)Math.cos(randomAngle) * 5f;
                    this.vy = (float)Math.sin(randomAngle) * 5f;
                } else if (type == TYPE_INF) {
                    this.radius = 22f;
                    this.color = 0xFF00AAFF;
                } else if (type == TYPE_DROPPED) {
                    this.radius = 12f;
                } else {
                    this.radius = 10f;
                    this.color = PALETTE[random.nextInt(PALETTE.length)];
                }
            }
        }

        // =====================================================================
        //  FloatingText
        // =====================================================================
        class FloatingText {
            String text;
            float  worldX, worldY;
            int    color, alpha, size;

            FloatingText(String text, float wx, float wy, int color, float size, int alpha) {
                this.text   = text;
                this.worldX = wx;
                this.worldY = wy;
                this.color  = color;
                this.size   = (int) size;
                this.alpha  = alpha;
            }
        }
    }
}