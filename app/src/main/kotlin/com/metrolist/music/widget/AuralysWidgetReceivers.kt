/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import dagger.hilt.android.AndroidEntryPoint

/*
 * One receiver per size in the widget list, so picking "2x2" or "4x1" there places a widget of that size right away.
 * They all behave the same (the size only decides the first shape; every widget can still be resized by dragging) and
 * all draw through AuralysWidgetRenderer, which lays itself out for whatever size the launcher gives it.
 * The 4x2 one is AuralysWidgetReceiver itself, so widgets placed before this split keep working.
 */

/** 2x2 */
@AndroidEntryPoint
class AuralysWidgetSquareReceiver : AuralysWidgetReceiver()

/** 4x1 */
@AndroidEntryPoint
class AuralysWidgetStripReceiver : AuralysWidgetReceiver()

/** 2x4 (Android 12+) */
@AndroidEntryPoint
class AuralysWidgetTallReceiver : AuralysWidgetReceiver()

/** 1x1 (Android 12+) */
@AndroidEntryPoint
class AuralysWidgetTinyReceiver : AuralysWidgetReceiver()
