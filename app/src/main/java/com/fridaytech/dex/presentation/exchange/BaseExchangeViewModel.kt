package com.fridaytech.dex.presentation.exchange

import androidx.lifecycle.MutableLiveData
import com.fridaytech.dex.App
import com.fridaytech.dex.R
import com.fridaytech.dex.core.model.Coin
import com.fridaytech.dex.core.ui.CoreViewModel
import com.fridaytech.dex.core.ui.SingleLiveEvent
import com.fridaytech.dex.data.adapter.FeeRatePriority
import com.fridaytech.dex.data.adapter.SendStateError
import com.fridaytech.dex.data.zrx.IRelayerAdapter
import com.fridaytech.dex.presentation.exchange.confirm.ExchangeConfirmInfo
import com.fridaytech.dex.presentation.exchange.model.ExchangeAmountInfo
import com.fridaytech.dex.presentation.exchange.model.ExchangeCoinItem
import com.fridaytech.dex.presentation.exchange.model.ExchangePairsInfo
import com.fridaytech.dex.presentation.exchange.model.IExchangeViewState
import com.fridaytech.dex.presentation.model.AmountInfo
import com.fridaytech.dex.presentation.orders.model.EOrderSide
import com.fridaytech.dex.utils.rx.uiSubscribe
import io.reactivex.disposables.Disposable
import java.math.BigDecimal
import java.math.RoundingMode

// TODO: This class needs refactoring
abstract class BaseExchangeViewModel<T : IExchangeViewState> : CoreViewModel() {
    protected val ratesConverter = App.ratesConverter
    private val relayerManager = App.relayerAdapterManager
    private val coinManager = App.coinManager
    private var adaptersDisposable: Disposable? = null
    protected val relayer: IRelayerAdapter?
        get() = relayerManager.mainRelayer

    private val adapterManager = App.adapterManager

    protected abstract var state: T
    protected val mReceiveInfo = ExchangeAmountInfo(BigDecimal.ZERO)

    protected val orderSide: EOrderSide
        get() {
            val market = marketCodes[currentMarketPosition]

            return if (market.first == state.sendCoin?.code) {
                EOrderSide.BUY
            } else {
                EOrderSide.SELL
            }
        }

    protected var exchangeableCoins: List<Coin> = listOf()
    protected var marketCodes: List<Pair<String, String>> = listOf()
    protected val currentMarketPosition: Int
        get() {
            val sendCoin = viewState.value?.sendCoin?.code ?: ""
            val receiveCoin = viewState.value?.receiveCoin?.code ?: ""

            return marketCodes.indexOfFirst {
                (it.first == sendCoin && it.second == receiveCoin) ||
                        (it.second == sendCoin && it.first == receiveCoin)
            }
        }

    private var mSendCoins: List<ExchangeCoinItem> = listOf()
        set(value) {
            field = value
            sendCoins.postValue(
                ExchangePairsInfo(
                    value,
                    state.sendCoin
                )
            )
        }

    private var mReceiveCoins: List<ExchangeCoinItem> = listOf()
        set(value) {
            field = value
            receiveCoins.postValue(
                ExchangePairsInfo(
                    value,
                    state.receiveCoin
                )
            )
        }

    val sendCoins = MutableLiveData<ExchangePairsInfo>()
    val receiveCoins = MutableLiveData<ExchangePairsInfo>()
    val viewState = MutableLiveData<T>()
    val sendHintInfo = MutableLiveData<AmountInfo>()
    val receiveAmount = MutableLiveData<ExchangeAmountInfo>()
    val exchangeEnabled = MutableLiveData<Boolean>()
    val exchangePrice = MutableLiveData<BigDecimal>()

    val transactionsSentEvent = SingleLiveEvent<String>()
    val confirmEvent = SingleLiveEvent<ExchangeConfirmInfo>()
    val showProcessingEvent = SingleLiveEvent<Unit>()
    val processingDismissEvent = SingleLiveEvent<Unit>()
    val focusExchangeEvent = SingleLiveEvent<Unit>()

    protected abstract fun initState(sendItem: ExchangeCoinItem?, receiveItem: ExchangeCoinItem?)
    protected abstract fun updateReceiveAmount()

    protected fun init() {
        exchangeEnabled.value = false

        relayerManager.mainRelayerUpdatedSignal.subscribe {
            relayer?.pairsSyncSubject?.subscribe {
                initData()
            }?.let { disposables.add(it) }
        }.let { disposables.add(it) }

        initData()
    }

    protected fun BigDecimal.scaleToView(): BigDecimal = setScale(
        9, RoundingMode.FLOOR
    ).stripTrailingZeros()

    private fun initData() {
        adaptersDisposable?.dispose()
        adaptersDisposable = null

        marketCodes = relayer?.exchangePairs?.map { it.baseCoinCode to it.quoteCoinCode } ?: listOf()

        exchangeableCoins = coinManager.coins.filter { coin ->
            marketCodes.firstOrNull { pair ->
                pair.first.equals(coin.code, true) ||
                        pair.second.equals(coin.code, true)
            } != null
        }

        refreshPairs(null)

        initState(mSendCoins.firstOrNull(), mReceiveCoins.firstOrNull())

        adapterManager.adaptersUpdatedSignal.subscribe {
            refreshPairs(viewState.value)
            exchangeableCoins.forEach { coin ->
                val adapter = adapterManager.adapters.firstOrNull { it.coin.code == coin.code }

                adapter?.balanceUpdatedFlowable
                    ?.uiSubscribe(disposables, {
                        refreshPairs(viewState.value)
                    })
            }
        }.let { adaptersDisposable = it }
    }

    open fun onSendAmountChange(amount: BigDecimal) {
        if (state.sendAmount.stripTrailingZeros() != amount.stripTrailingZeros()) {
            state.sendAmount = amount

            updateReceiveAmount()

            updateSendHint(amount)
        }
    }

    //TODO: Add protocol fee to validation process
    protected fun updateSendHint(amount: BigDecimal) {
        val adapter = adapterManager.adapters
            .firstOrNull { it.coin.code == state.sendCoin?.code }

        val info = AmountInfo(
            ratesConverter.getCoinsPrice(adapter?.coin?.code ?: "", amount),
            0
        )

        adapter?.validate(amount, null, FeeRatePriority.HIGHEST)?.forEach {
            when (it) {
                is SendStateError.InsufficientFeeBalance -> {
                    info.error = R.string.error_insufficient_fee_balance
                }
                is SendStateError.InsufficientAmount -> {
                    info.error = R.string.error_insufficient_balance
                }
            }
        }

        sendHintInfo.value = info
    }

    fun onMaxClick() {
        val adapter = adapterManager.adapters.firstOrNull { it.coin.code == state.sendCoin?.code }
        if (adapter != null) {
            val amount = adapter.availableBalance(null, FeeRatePriority.MEDIUM)
            onSendAmountChange(amount.scaleToView())
            viewState.value = state
        }
    }

    open fun onReceiveCoinPick(position: Int, forceChange: Boolean = false) {
        if (state.receiveCoin?.code != mReceiveCoins[position].code) {
            state.receiveCoin = mReceiveCoins[position]
            updateReceiveAmount()

            if (forceChange) {
                viewState.value = state
            }
        }
    }

    open fun onSendCoinPick(position: Int) {
        val pair = mSendCoins[position]

        if (pair.code == state.receiveCoin?.code) {
            state.sendCoin = pair
            state.receiveCoin = null

            refreshPairs(state, false)
            viewState.value = state
        } else if (state.sendCoin?.code != pair.code) {
            state.sendCoin = mSendCoins[position]

            refreshPairs(state, false)

            viewState.value = state
            updateReceiveAmount()
        }
    }

    //region Market pairs refresh

    protected open fun refreshPairs(state: T?, refreshSendCoins: Boolean = true) {
        if (refreshSendCoins) {
            mSendCoins = getAvailableSendCoins()
        }

        if (mSendCoins.isNotEmpty()) {
            val sendCoin = state?.sendCoin?.code ?: mSendCoins.first().code
            mReceiveCoins = getAvailableReceiveCoins(sendCoin)

            val currentReceiveIndex = mReceiveCoins.indexOfFirst { it.code == state?.receiveCoin?.code }

            if (currentReceiveIndex < 0 || this.state.receiveCoin == null) {
                this.state.receiveCoin = mReceiveCoins.firstOrNull()
                receiveCoins.postValue(
                    ExchangePairsInfo(
                        mReceiveCoins,
                        state?.receiveCoin
                    )
                )
            }
        }
    }

    private fun getAvailableSendCoins(): List<ExchangeCoinItem> = exchangeableCoins
        .map { getExchangeItem(it) }

    private fun getAvailableReceiveCoins(baseCoinCode: String): List<ExchangeCoinItem> =
        exchangeableCoins.filter { coin ->
            marketCodes.firstOrNull {
                val isBuySide = it.first.equals(baseCoinCode, true) &&
                        it.second.equals(coin.code, true)
                val isSellSide = it.second.equals(baseCoinCode, true) &&
                        it.first.equals(coin.code, true)

                isBuySide || isSellSide
            } != null
        }.map { getExchangeItem(it) }

    protected fun getExchangeItem(coin: Coin): ExchangeCoinItem {
        val balance = adapterManager.adapters
            .firstOrNull { it.coin.code == coin.code }?.balance ?: BigDecimal.ZERO

        return ExchangeCoinItem(
            coin.code,
            coin.title,
            BigDecimal.ZERO,
            balance
        )
    }

    //endregion

    override fun onCleared() {
        super.onCleared()
        adaptersDisposable?.dispose()
    }
}
