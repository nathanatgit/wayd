package com.nathanhanapps.wayd.feature.apps

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.feature.EmptyScreen

@Composable
fun AppsScreen() = EmptyScreen(
    title = stringResource(R.string.apps_title),
    message = stringResource(R.string.apps_empty),
)
