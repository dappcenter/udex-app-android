package com.blocksdecoded.dex.core.manager.zrx

import com.blocksdecoded.dex.core.manager.ICoinManager
import com.blocksdecoded.dex.core.manager.zrx.model.RelayerOrders
import com.blocksdecoded.dex.core.manager.zrx.model.RelayerOrdersList
import com.blocksdecoded.dex.core.model.CoinType
import com.blocksdecoded.dex.presentation.orders.model.EOrderSide
import com.blocksdecoded.dex.utils.ioSubscribe
import com.blocksdecoded.zrxkit.ZrxKit
import com.blocksdecoded.zrxkit.contracts.ZrxExchangeWrapper
import com.blocksdecoded.zrxkit.model.OrderInfo
import com.blocksdecoded.zrxkit.model.SignedOrder
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

class BaseRelayerAdapter(
	private val coinManager: ICoinManager,
	private val ethereumKit: EthereumKit,
	private val exchangeWrapper: ZrxExchangeWrapper,
	zrxKit: ZrxKit,
	allowanceChecker: IAllowanceChecker,
	override val refreshInterval: Long,
	override val relayerId: Int
): IRelayerAdapter {
	private val disposables = CompositeDisposable()
	
	private val relayerManager = zrxKit.relayerManager
	private val relayer = relayerManager.availableRelayers[relayerId]
	private val exchangeInteractor = ExchangeInteractor(coinManager, ethereumKit, zrxKit, exchangeWrapper, allowanceChecker)

	override var myOrdersInfo =
		RelayerOrdersList<OrderInfo>()
	override var buyOrders =
		RelayerOrdersList<SignedOrder>()
	override var sellOrders =
		RelayerOrdersList<SignedOrder>()
	override var myOrders =
		RelayerOrdersList<Pair<SignedOrder, EOrderSide>>()

	override val availablePairs = relayer.availablePairs
	override val availablePairsSubject: BehaviorSubject<List<Pair<String, String>>> =
		BehaviorSubject.create()
	
	init {
		val pairs = relayer.availablePairs.map {
			(coinManager.getErcCoinForAddress(it.first.address)?.code ?: "") to
					(coinManager.getErcCoinForAddress(it.second.address)?.code ?: "")
		}
		availablePairsSubject.onNext(pairs)

		Observable.interval(refreshInterval, TimeUnit.SECONDS)
			.subscribeOn(Schedulers.io())
			.observeOn(Schedulers.io())
			.subscribe { refreshOrders() }
			.let { disposables.add(it) }

		refreshOrders()
	}

	//region Private

	private fun getErcCoin(coinCode: String): CoinType.Erc20 =
		coinManager.getCoin(coinCode).type as CoinType.Erc20

	private fun refreshOrders() {
		relayer.availablePairs.forEachIndexed { index, pair ->
			val base = pair.first.assetData
			val quote = pair.second.assetData

			refreshPair(base, quote)
		}
	}

	private fun refreshPair(baseAsset: String, quoteAsset: String) {
		relayerManager.getOrderbook(relayerId, baseAsset, quoteAsset)
			.ioSubscribe(disposables, {
				buyOrders.updatePairOrders(baseAsset, quoteAsset, it.bids.records.map { it.order })
				sellOrders.updatePairOrders(baseAsset, quoteAsset, it.asks.records.map { it.order })

				val address = ethereumKit.receiveAddress.toLowerCase()

				val myOrders = it.asks.records
					.map { it.order }
					.filter { it.makerAddress.equals(address, true) }
					.map { it to EOrderSide.SELL }
					.plus(it.bids.records.map { it.order }.filter { it.makerAddress.equals(address, true) }.map { it to EOrderSide.BUY })

				this.myOrders.updatePairOrders(baseAsset, quoteAsset, myOrders)

				exchangeWrapper.ordersInfo(myOrders.map { it.first })
					.ioSubscribe(disposables, { ordersInfo ->
						myOrdersInfo.updatePairOrders(baseAsset, quoteAsset, ordersInfo)
						this.myOrders.updatePairOrders(baseAsset, quoteAsset, myOrders)
					}, { })
			})
	}

	private fun getPairOrders(coinPair: Pair<String, String>, side: EOrderSide): RelayerOrders<SignedOrder> {
		val baseCoin = getErcCoin(coinPair.first)
		val quoteCoin = getErcCoin(coinPair.second)

		return when(side) {
			EOrderSide.BUY -> buyOrders
			else -> sellOrders
		}.getPair(
			ZrxKit.assetItemForAddress(baseCoin.address).assetData,
			ZrxKit.assetItemForAddress(quoteCoin.address).assetData
		)
	}

	//endregion
	
	//region Public

	//region Exchange

	override fun createOrder(
		coinPair: Pair<String, String>,
		side: EOrderSide,
		amount: BigDecimal,
		price: BigDecimal
	): Flowable<SignedOrder> =
		exchangeInteractor.createOrder(relayer.feeRecipients.first(), coinPair, side, amount, price)

	override fun fill(coinPair: Pair<String, String>, side: EOrderSide, amount: BigDecimal): Flowable<String> {
		val orders = when(side) {
			EOrderSide.BUY -> buyOrders
			else -> sellOrders
		}

		return exchangeInteractor.fill(orders, coinPair, side, amount)
	}

	override fun cancelOrder(order: SignedOrder): Flowable<String> =
		exchangeInteractor.cancelOrder(order)

	override fun calculateBasePrice(coinPair: Pair<String, String>, side: EOrderSide): BigDecimal = try {
		val baseCoin = getErcCoin(coinPair.first)
		val quoteCoin = getErcCoin(coinPair.second)

		val pairOrders = getPairOrders(coinPair, side)

		val makerAmount = pairOrders.orders.first().makerAssetAmount.toBigDecimal()
			.movePointLeft(if (side == EOrderSide.BUY) quoteCoin.decimal else baseCoin.decimal)
			.stripTrailingZeros()

		val takerAmount = pairOrders.orders.first().takerAssetAmount.toBigDecimal()
			.movePointLeft(if (side == EOrderSide.BUY) baseCoin.decimal else quoteCoin.decimal)
			.stripTrailingZeros()

        //TODO: Update price calculation
        val math = MathContext.DECIMAL32
        val price = makerAmount.toDouble().div(takerAmount.toDouble())
            .toBigDecimal()
            .setScale(18, RoundingMode.UP)
            .stripTrailingZeros()

		price
	} catch (e: Exception) {
		BigDecimal.ZERO
	}

	//endregion

	override fun calculateFillAmount(coinPair: Pair<String, String>, side: EOrderSide, amount: BigDecimal): BigDecimal = try {
		val price = calculateBasePrice(coinPair, side)
		amount.multiply(price)
	} catch (e: Exception) {
		BigDecimal.ZERO
	}

	override fun stop() {
		disposables.clear()
		buyOrders.clear()
		sellOrders.clear()
		myOrders.clear()
		myOrdersInfo.clear()
	}

	//endregion
}