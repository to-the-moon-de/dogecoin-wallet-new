package de.langerhans.wallet.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.*;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import android.preference.PreferenceManager;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import com.actionbarsherlock.app.SherlockFragment;
import com.google.dogecoin.core.*;
import com.google.dogecoin.crypto.TransactionSignature;
import com.google.dogecoin.script.Script;
import com.google.dogecoin.script.ScriptBuilder;
import de.langerhans.wallet.*;
import de.langerhans.wallet.service.BlockchainService;
import de.langerhans.wallet.service.BlockchainServiceImpl;
import de.langerhans.wallet.util.GenericUtils;
import de.langerhans.wallet.util.Io;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * @author Maximilian Keller
 */
public class SweepKeyFragment extends SherlockFragment {

    private AbstractBindServiceActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private BlockchainService service;

    private State state = State.INPUT;
    private Transaction sweepTransaction = null;
    private boolean isBound = false;

    private CurrencyCalculatorLink amountCalculatorLink;

    private TransactionsListAdapter sweepTransactionListAdapter;
    private ListView sweepTransactionView;
    private Button viewGo;
    private Button viewCancel;
    private TextView sweepErorr;

    private ArrayList<UnspentOutput> unspentOutputs = new ArrayList<UnspentOutput>();

    private ECKey key = null;
    private BigInteger balance = BigInteger.ZERO;
    private BigInteger unconfBalance = BigInteger.ZERO;

    private static final Logger log = LoggerFactory.getLogger(SweepKeyFragment.class);

    private static final BigInteger KB_DIVISOR = BigInteger.valueOf(1000);

    private static final int ID_RATE_LOADER = 0;
    private enum State
    {
        INPUT, PREPARATION, SENDING, SENT, FAILED
    }

    @Override
    public void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        //setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.sweep_key_fragment, container);

        final CurrencyAmountView dogeAmountView = (CurrencyAmountView) view.findViewById(R.id.sweep_balance_doge);
        dogeAmountView.setCurrencySymbol(config.getBtcPrefix());
        dogeAmountView.setInputPrecision(config.getBtcMaxPrecision());
        dogeAmountView.setHintPrecision(config.getBtcPrecision());
        dogeAmountView.setShift(config.getBtcShift());

        final CurrencyAmountView fiatAmountView = (CurrencyAmountView) view.findViewById(R.id.sweep_balnce_fiat);
        fiatAmountView.setInputPrecision(Constants.LOCAL_PRECISION);
        fiatAmountView.setHintPrecision(Constants.LOCAL_PRECISION);
        amountCalculatorLink = new CurrencyCalculatorLink(dogeAmountView, fiatAmountView);
        amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());

        sweepTransactionView = (ListView) view.findViewById(R.id.sweep_key_sent_transaction);
        sweepTransactionListAdapter = new TransactionsListAdapter(activity, wallet, application.maxConnectedPeers(), false);
        sweepTransactionView.setAdapter(sweepTransactionListAdapter);

        viewGo = (Button) view.findViewById(R.id.send_coins_go);
        viewGo.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(final View v)
            {
                isAmountValid();
                if (everythingValid())
                    handleGo();
            }
        });

        amountCalculatorLink.setNextFocusId(viewGo.getId());

        viewCancel = (Button) view.findViewById(R.id.send_coins_cancel);
        viewCancel.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(final View v)
            {
                if (state == State.INPUT)
                    activity.setResult(Activity.RESULT_CANCELED);

                activity.finish();
            }
        });

        if (savedInstanceState != null)
        {
            restoreInstanceState(savedInstanceState);
        }
        else
        {
            final Intent intent = activity.getIntent();

            if (intent.hasExtra(SweepKeyActivity.INTENT_EXTRA_KEY))
                key = (ECKey)intent.getSerializableExtra(SweepKeyActivity.INTENT_EXTRA_KEY);
        }

        sweepErorr = (TextView) view.findViewById(R.id.sweep_error);

        return view;
    }

    @Override
    public void onAttach(final Activity activity)
    {
        super.onAttach(activity);

        this.activity = (AbstractBindServiceActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();
        this.config = new Configuration(PreferenceManager.getDefaultSharedPreferences(activity));
    }

    @Override
    public void onDestroyView()
    {
        super.onDestroyView();
        config.setLastExchangeDirection(amountCalculatorLink.getExchangeDirection());
    }

    @Override
    public void onResume()
    {
        super.onResume();
        //amountCalculatorLink.setListener(amountsListener);
        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        updateView();
        new BalanceRequestTask().execute(key.toAddress(Constants.NETWORK_PARAMETERS).toString());
    }

    @Override
    public void onPause()
    {
        loaderManager.destroyLoader(ID_RATE_LOADER);
        amountCalculatorLink.setListener(null);
        super.onPause();
    }

    @Override
    public void onDetach()
    {
        super.onDetach();
    }

    @Override
    public void onDestroy()
    {
        if (sweepTransaction != null)
            sweepTransaction.getConfidence().removeEventListener(sweepTransactionConfidenceListener);
        if (isBound)
            activity.unbindService(serviceConnection);
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(final Bundle outState)
    {
        super.onSaveInstanceState(outState);
        saveInstanceState(outState);
    }

    private void saveInstanceState(final Bundle outState)
    {
        outState.putSerializable("state", state);
        if (key != null)
            outState.putSerializable("key", key);
    }

    private void restoreInstanceState(final Bundle savedInstanceState)
    {
        state = (State) savedInstanceState.getSerializable("state");
        if (savedInstanceState.containsKey("key"))
            key = (ECKey)savedInstanceState.getSerializable("key");
    }

    private final TransactionConfidence.Listener sweepTransactionConfidenceListener = new TransactionConfidence.Listener()
    {
        @Override
        public void onConfidenceChanged(final Transaction tx, final TransactionConfidence.Listener.ChangeReason reason)
        {
            activity.runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    sweepTransactionListAdapter.notifyDataSetChanged();

                    final TransactionConfidence confidence = sweepTransaction.getConfidence();
                    final TransactionConfidence.ConfidenceType confidenceType = confidence.getConfidenceType();
                    final int numBroadcastPeers = confidence.numBroadcastPeers();

                    if (state == State.SENDING)
                    {
                        if (confidenceType == TransactionConfidence.ConfidenceType.DEAD)
                            state = State.FAILED;
                        else if (numBroadcastPeers > 1 || confidenceType == TransactionConfidence.ConfidenceType.BUILDING)
                            state = State.SENT;

                        updateView();
                    }

                    if (reason == ChangeReason.SEEN_PEERS && confidenceType == TransactionConfidence.ConfidenceType.PENDING)
                    {
                        // play sound effect
                        final int soundResId = getResources().getIdentifier("send_coins_broadcast_" + numBroadcastPeers, "raw",
                                activity.getPackageName());
                        if (soundResId > 0)
                            RingtoneManager.getRingtone(activity, Uri.parse("android.resource://" + activity.getPackageName() + "/" + soundResId))
                                    .play();
                    }
                }
            });
        }
    };

    private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>()
    {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args)
        {
            return new ExchangeRateLoader(activity, config);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data)
        {
            if (data != null && data.getCount() > 0)
            {
                data.moveToFirst();
                final ExchangeRatesProvider.ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);

                if (state == State.INPUT)
                    amountCalculatorLink.setExchangeRate(exchangeRate);
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader)
        {
        }
    };

    private final CurrencyAmountView.Listener amountsListener = new CurrencyAmountView.Listener()
    {
        @Override
        public void changed()
        {
            updateView();
        }

        @Override
        public void focusChanged(final boolean hasFocus)
        {
        }
    };

    private final DialogInterface.OnClickListener activityDismissListener = new DialogInterface.OnClickListener()
    {
        @Override
        public void onClick(final DialogInterface dialog, final int which)
        {
            activity.finish();
        }
    };
    private boolean isAmountValid()
    {
        final BigInteger amount = amountCalculatorLink.getAmount();
        return amount != null && amount.signum() > 0;
    }

    private boolean everythingValid()
    {
        return state == State.INPUT && isAmountValid();
    }

    private void updateView()
    {
        amountCalculatorLink.setEnabled(false);

        if (sweepTransaction != null)
        {
            final int btcPrecision = config.getBtcPrecision();
            final int btcShift = config.getBtcShift();

            sweepTransactionView.setVisibility(View.VISIBLE);
            sweepTransactionListAdapter.setPrecision(btcPrecision, btcShift);
            sweepTransactionListAdapter.replace(sweepTransaction);
        }
        else
        {
            sweepTransactionView.setVisibility(View.GONE);
            sweepTransactionListAdapter.clear();
        }

        if (state == State.INPUT)
        {
            amountCalculatorLink.setBtcAmount(balance);
            viewCancel.setText(R.string.button_cancel);
            viewGo.setText(R.string.sweep_sweep);
        }
        else if (state == State.PREPARATION)
        {
            viewCancel.setText(R.string.button_cancel);
            viewGo.setText(R.string.send_coins_preparation_msg);
        }
        else if (state == State.SENDING)
        {
            viewCancel.setText(R.string.send_coins_fragment_button_back);
            viewGo.setText(R.string.send_coins_sending_msg);
        }
        else if (state == State.SENT)
        {
            viewCancel.setText(R.string.send_coins_fragment_button_back);
            viewGo.setText(R.string.send_coins_sent_msg);
        }
        else if (state == State.FAILED)
        {
            if (unconfBalance.signum() == 1)
            {
                String error = activity.getString(R.string.sweep_unconfirmed, GenericUtils.formatValue(unconfBalance, config.getBtcPrecision(), config.getBtcShift()));
                sweepErorr.setText(error);
            }
            else if(balance.signum() == 0 && !(unconfBalance.signum() == 1))
            {
                sweepErorr.setText(activity.getString(R.string.sweep_zero));
            }else
            {
                sweepErorr.setText(R.string.sweep_getbalance_failed);
            }

            viewCancel.setText(R.string.send_coins_fragment_button_back);
            viewGo.setText(R.string.send_coins_failed_msg);
        }

        viewCancel.setEnabled(state != State.PREPARATION);
        viewGo.setEnabled(everythingValid());
    }

    private void handleGo()
    {
        if (unconfBalance.signum() == 1) {
            state = State.FAILED;
            updateView();
        }

        sweepTransaction = new Transaction(Constants.NETWORK_PARAMETERS);
        ArrayList<Script> scripts = new ArrayList<Script>();
        BigInteger fee = BigInteger.ZERO;

        // initialize tx size counter
        BigInteger txSize = BigInteger.ZERO;

        // estimated size of signature
        // derived from https://github.com/langerhans/dogecoinj-new/blob/master/core/src/main/java/com/google/dogecoin/core/Wallet.java#L3650
        BigInteger sigSize = BigInteger.valueOf(key.getPubKeyHash().length + 75);

        for (UnspentOutput out : unspentOutputs)
        {
            Sha256Hash hash = new Sha256Hash(Hex.decode(out.getTxHash()));
            sweepTransaction.addInput(
                    new TransactionInput(
                            Constants.NETWORK_PARAMETERS,
                            sweepTransaction,
                            new byte[]{},
                            new TransactionOutPoint(
                                    Constants.NETWORK_PARAMETERS,
                                    out.getTxOutputN(),
                                    hash
                            )
                    )
            );

            byte[] scriptBytes = Hex.decode(out.getScript());
            scripts.add(new Script(scriptBytes));

            //add script size and signature size for every input to the tx size counter
            txSize = txSize.add(BigInteger.valueOf(scriptBytes.length)).add(sigSize);
        }

        Address myAddress = application.determineSelectedAddress();

        // initially, add the full balance to the tx
        sweepTransaction.addOutput(balance, myAddress);

        // add size of the serialized transaction to the tx
        // note that this figure by itself excludes scripts and signature data
        //TODO at this point we are still missing ~18 bytes per input, this should be fixed
        txSize = txSize.add(BigInteger.valueOf(sweepTransaction.bitcoinSerialize().length));

        // integer division by 1000, rounding up: (x+y-1)/y
        BigInteger sizeKb = txSize.add(KB_DIVISOR.subtract(BigInteger.ONE)).divide(KB_DIVISOR);

        // calculate fee by multiplying kilobyte size with default_min_tx_fee
        // note that result is in doge * 1e8
        fee = fee.add(sizeKb.multiply(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE));

        // Since we always just have one output for a sweep,
        // we set the value of vout[0] to balance - fee
        sweepTransaction.getOutput(0).setValue(balance.subtract(fee));

        state = State.PREPARATION;
        updateView();

        signTransactionInputs(sweepTransaction, Transaction.SigHash.ALL, key, scripts);
        sweepTransaction.getConfidence().addEventListener(sweepTransactionConfidenceListener);

        // Now bind to the service so we can broadcast the transaction
        activity.bindService(new Intent(activity, BlockchainServiceImpl.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder binder)
        {
            service = ((BlockchainServiceImpl.LocalBinder) binder).getService();
            isBound = true;
            service.broadcastSweepTransaction(sweepTransaction);
            state = State.SENDING;
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateView();
                }
            });
        }

        @Override
        public void onServiceDisconnected(final ComponentName name)
        {
            isBound = false;
            service = null;
        }
    };

    // Taken from https://github.com/ksedgwic/Wallet32/blob/master/src/com/bonsai/wallet32/WalletUtil.java
    // TODO: Move this somewehere else!
    // Thanks to devrandom!
    public static void signTransactionInputs(Transaction tx,
                                             Transaction.SigHash hashType,
                                             ECKey key,
                                             List<Script> inputScripts) throws ScriptException {

        List<TransactionInput> inputs = tx.getInputs();
        List<TransactionOutput> outputs = tx.getOutputs();

        checkState(inputs.size() > 0);
        checkState(outputs.size() > 0);

        checkArgument(hashType == Transaction.SigHash.ALL, "Only SIGHASH_ALL is currently supported");

        // The transaction is signed with the input scripts empty
        // except for the input we are signing. In the case where
        // addInput has been used to set up a new transaction, they
        // are already all empty. The input being signed has to have
        // the connected OUTPUT program in it when the hash is
        // calculated!
        //
        // Note that each input may be claiming an output sent to a
        // different key. So we have to look at the outputs to figure
        // out which key to sign with.

        TransactionSignature[] signatures = new TransactionSignature[inputs.size()];
        ECKey[] signingKeys = new ECKey[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            TransactionInput input = inputs.get(i);
            // We don't have the connected output, we assume it was
            // signed already and move on
            if (input.getScriptBytes().length != 0)
                log.warn("Re-signing an already signed transaction! Be sure this is what you want.");

            // This assert should never fire. If it does, it means the wallet is inconsistent.
            checkNotNull(key, "Transaction exists in wallet that we cannot redeem: %s",
                    input.getOutpoint().getHash());

            // Keep the key around for the script creation step below.
            signingKeys[i] = key;

            // The anyoneCanPay feature isn't used at the moment.
            boolean anyoneCanPay = false;
            signatures[i] = tx.calculateSignature(i, key, inputScripts.get(i), hashType, anyoneCanPay);
        }

        // Now we have calculated each signature, go through and
        // create the scripts. Reminder: the script consists:
        //
        // 1) For pay-to-address outputs: a signature (over a hash of
        // the simplified transaction) and the complete public key
        // needed to sign for the connected output. The output script
        // checks the provided pubkey hashes to the address and then
        // checks the signature.
        //
        // 2) For pay-to-key outputs: just a signature.
        //
        for (int i = 0; i < inputs.size(); i++) {
            if (signatures[i] == null)
                continue;
            TransactionInput input = inputs.get(i);
            Script scriptPubKey = inputScripts.get(i);
            if (scriptPubKey.isSentToAddress()) {

                input.setScriptSig(ScriptBuilder.createInputScript(signatures[i],
                        signingKeys[i]));
            } else if (scriptPubKey.isSentToRawPubKey()) {

                input.setScriptSig(ScriptBuilder.createInputScript(signatures[i]));
            } else {
                // Should be unreachable - if we don't recognize
                // the type of script we're trying to sign for,
                // then we should have failed above when fetching
                // the key to sign with.
                throw new RuntimeException("Do not understand script type: " + scriptPubKey);
            }
        }

        // Every input is now complete.
    }

    private class BalanceRequestTask extends AsyncTask<String, Integer, Integer>
    {
        ProgressDialog progress;
        String dogechainApi = "https://dogechain.info/unspent/%s";
        @Override
        protected void onPreExecute () {
            progress = new ProgressDialog(activity);
            progress.setIndeterminate(true);
            progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            progress.setTitle(R.string.sweep_getbalance_title);
            progress.setMessage(activity.getString(R.string.sweep_getbalance_text));
            progress.show();

            unspentOutputs.clear();
        }

        @Override
        protected Integer doInBackground(String... address) {
            long start = System.currentTimeMillis();
            HttpURLConnection connection = null;
            Reader reader = null;

            try
            {
                dogechainApi = String.format(dogechainApi, address);
                URL url = new URL(dogechainApi);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
                connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
                connection.connect();

                final int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK)
                {
                    reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024), Constants.UTF_8);
                    final StringBuilder content = new StringBuilder();
                    Io.copy(reader, content);

                    try
                    {
                        final JSONObject json = new JSONObject(content.toString());

                        Integer success = json.getInt("success");
                        if (success != 1)
                            return -1;

                        JSONArray unspentJson = json.getJSONArray("unspent_outputs");
                        if(unspentJson.length() <= 0)
                            return 0;

                        for (int i = 0 ; i < unspentJson.length(); i++)
                        {
                            JSONObject output = unspentJson.getJSONObject(i);
                            UnspentOutput out = new UnspentOutput(
                                    output.getString("tx_hash"),
                                    output.getInt("tx_output_n"),
                                    output.getString("script"),
                                    new BigInteger(output.getString("value")),
                                    output.getInt("confirmations")
                            );
                            unspentOutputs.add(out);
                        }

                    } catch (Exception e)
                    {
                        log.debug("Error while reading the JSON response");
                        return -1;
                    }

                }
                else
                {
                    log.debug("http status " + responseCode + " when fetching unspent outputs");
                }
            }
            catch (final Exception x)
            {
                log.debug("problem reading unspent outputs", x);
            }
            finally
            {
                if (reader != null)
                {
                    try
                    {
                        reader.close();
                    }
                    catch (final IOException x)
                    {
                        // swallow
                    }
                }

                if (connection != null)
                    connection.disconnect();

            }

            long stop = System.currentTimeMillis();
            if (stop - start < 1500)
                try {
                    Thread.sleep(1500 - (stop-start)); //Need to promote dogechain a bit :D
                } catch (InterruptedException e) {
                    //ignore
                }
            return 1;
        }

        @Override
        protected void onPostExecute(Integer result) {
            progress.dismiss();
            switch (result)
            {
                case -1:
                case 0:
                    state = State.FAILED;
            }

            balance = BigInteger.ZERO;
            unconfBalance = BigInteger.ZERO;

            for (UnspentOutput out : unspentOutputs)
            {
                if (out.getConfirmations() >= 3)
                    balance = balance.add(out.getValue());
                else
                    unconfBalance = unconfBalance.add(out.getValue());
            }

            if (unconfBalance.signum() == 1)
                state = State.FAILED;

            updateView();
        }

    }

    private class UnspentOutput
    {
        private String txHash;
        private Integer txOutputN;
        private String script;
        private BigInteger value;
        private Integer confirmations;

        private UnspentOutput(String txHash, Integer txOutputN, String script, BigInteger value, Integer confirmations) {
            this.txHash = txHash;
            this.txOutputN = txOutputN;
            this.script = script;
            this.value = value;
            this.confirmations = confirmations;
        }

        public String getTxHash() {
            return txHash;
        }

        public Integer getTxOutputN() {
            return txOutputN;
        }

        public String getScript() {
            return script;
        }

        public BigInteger getValue() {
            return value;
        }

        public Integer getConfirmations() {
            return confirmations;
        }
    }
}