package com.blocksdecoded.dex.presentation.dialogs.convert

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.blocksdecoded.dex.R
import com.blocksdecoded.dex.presentation.dialogs.BaseBottomDialog
import com.blocksdecoded.dex.presentation.dialogs.convert.ConvertConfig.ConvertType.*
import com.blocksdecoded.dex.presentation.dialogs.sent.SentDialog
import com.blocksdecoded.dex.presentation.widgets.NumPadItem
import com.blocksdecoded.dex.presentation.widgets.NumPadItemType
import com.blocksdecoded.dex.presentation.widgets.NumPadItemsAdapter
import com.blocksdecoded.dex.presentation.widgets.click.setSingleClickListener
import com.blocksdecoded.dex.presentation.widgets.listeners.SimpleTextWatcher
import com.blocksdecoded.dex.utils.ui.ToastHelper
import com.blocksdecoded.dex.utils.ui.toDisplayFormat
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import kotlinx.android.synthetic.main.dialog_convert.*
import kotlinx.android.synthetic.main.dialog_send.*
import kotlinx.android.synthetic.main.view_amount_input.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

class ConvertDialog private constructor()
    : BaseBottomDialog(R.layout.dialog_convert), NumPadItemsAdapter.Listener {

    private lateinit var viewModel: ConvertViewModel
    lateinit var config: ConvertConfig
    
    private var inputConnection: InputConnection? = null
    private val amountChangeSubject: PublishSubject<BigDecimal> = PublishSubject.create()
    private var disposable: Disposable? = null

    private val amountChangeListener = object: SimpleTextWatcher() {
        override fun afterTextChanged(s: Editable?) {
            val amountText = s?.toString() ?: ""
            var amountNumber = when {
                amountText != "" -> amountText.toBigDecimalOrNull() ?: BigDecimal.ZERO
                else -> BigDecimal.ZERO
            }

            viewModel.decimalSize.let {
                if (amountNumber.scale() > it) {
                    amountNumber = amountNumber.setScale(it, RoundingMode.FLOOR)
                    val newString = amountNumber.toPlainString()
                    amount_input?.setText(newString)
                    amount_input?.setSelection(newString.length)
                }
            }

            send_amount?.setMaxBtnVisible(amountText.isEmpty())
            amountChangeSubject.onNext(amountNumber)
        }
    }
    
    @SuppressLint("SetTextI18n")
    private fun updateState(state: ConvertState) {
        convert_title?.text = when (state.type) {
            WRAP -> "Wrap "
            UNWRAP -> "Unwrap "
        } + state.fromCoin.code
        
        convert_balance?.text = "${state.balance.toDisplayFormat()} ${state.fromCoin.code}"
        convert_coin_icon?.bind(state.fromCoin.code)
        convert_amount?.updateAmountPrefix(state.fromCoin.code)
    }
    
    private fun updateReceiveAmount(amount: BigDecimal) {
        if (amount > BigDecimal.ZERO) {
            convert_receive_amount?.text = amount.stripTrailingZeros().toPlainString()
        } else {
            convert_receive_amount?.text = ""
        }
    }
    
    private fun updateSendAmount(amount: BigDecimal) {
        if (amount > BigDecimal.ZERO) {
            amount_input?.setText(amount.stripTrailingZeros().toPlainString())
            amount_input?.setSelection(amount_input?.text?.length ?: 0)
        } else {
            amount_input?.setText("")
        }
    }
    
    //region Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        activity?.let {
            viewModel = ViewModelProviders.of(it).get(ConvertViewModel::class.java)
            viewModel.init(config)
        }
        
        disposable = amountChangeSubject.debounce(300, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { viewModel.onAmountChanged(it) }
        
        viewModel.convertState.observe(this, Observer { state ->
            updateState(state)
        })
        
        viewModel.errorEvent.observe(this, Observer {
            ToastHelper.showErrorMessage(it)
        })
        
        viewModel.messageEvent.observe(this, Observer {
            ToastHelper.showInfoMessage(it)
        })
        
        viewModel.amount.observe(this, Observer { amount -> updateSendAmount(amount) })
        
        viewModel.receiveAmount.observe(this, Observer {amount ->
            updateReceiveAmount(amount)
        })
        
        viewModel.convertEnabled.observe(this, Observer {
            convert_confirm?.isEnabled = it
        })
        
        viewModel.transactionSentEvent.observe(this, Observer { transactionHash ->
            activity?.let {
                SentDialog.open(it.supportFragmentManager, transactionHash)
            }
        })
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        convert_amount?.bindInitial( onMaxClick = {
            viewModel.onMaxClicked()
        }, onSwitchClick = {
        
        })
    
        convert_numpad?.bind(this, NumPadItemType.DOT, false)
    
        amount_input?.addTextChangedListener(amountChangeListener)
        amount_input?.showSoftInputOnFocus = false
        inputConnection = amount_input?.onCreateInputConnection(EditorInfo())
    
        convert_confirm?.setSingleClickListener { viewModel.onConvertClick() }
    }
    
    //endregion
    
    override fun onItemClick(item: NumPadItem) {
        when (item.type) {
            NumPadItemType.NUMBER -> inputConnection?.commitText(item.number.toString(), 1)
            NumPadItemType.DELETE -> inputConnection?.deleteSurroundingText(1, 0)
            NumPadItemType.DOT -> {
                if (amount_input?.text?.toString()?.contains(".") != true) {
                    inputConnection?.commitText(".", 1)
                }
            }
        }
    }
    
    companion object {
        fun open(fragmentManager: FragmentManager, config: ConvertConfig) {
            val fragment = ConvertDialog()

            fragment.config = config

            fragment.show(fragmentManager, "convert_${config.type}")
        }
    }
}