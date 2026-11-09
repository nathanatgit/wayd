package com.nathanhanapps.wayd.feature.reports

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.feature.EmptyScreen

@Composable
fun ReportsScreen() = EmptyScreen(
    title = stringResource(R.string.reports_title),
    message = stringResource(R.string.reports_empty),
)
