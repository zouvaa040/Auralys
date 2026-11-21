/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Page backdrop shared by the full-screen auth pages: ambient glow (glass) / paper grain (editorial). */
@Composable
fun loginBackdrop(base: Modifier): Modifier = base.ambientGlow().paperGrain()
