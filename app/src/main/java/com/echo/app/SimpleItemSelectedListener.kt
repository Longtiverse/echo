package com.echo.app

import android.view.View
import android.widget.AdapterView

class SimpleItemSelectedListener(
    private val onItemSelectedBlock: (position: Int) -> Unit
) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        onItemSelectedBlock(position)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
