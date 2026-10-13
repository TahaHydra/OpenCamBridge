package com.opencambridge.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opencambridge.android.R

/** Generated from branding/logo-master.png; product name remains separate. */
@Composable
fun LogoMark(size: Dp = 56.dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ocb_logo), contentDescription = null, modifier = modifier.size(size))
}
