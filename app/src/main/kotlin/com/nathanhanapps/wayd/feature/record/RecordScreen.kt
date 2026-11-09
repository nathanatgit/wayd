package com.nathanhanapps.wayd.feature.record

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.feature.EmptyScreen

@Composable
fun RecordScreen() = EmptyScreen(
    title = stringResource(R.string.record_title),
    message = stringResource(R.string.record_placeholder),
)
