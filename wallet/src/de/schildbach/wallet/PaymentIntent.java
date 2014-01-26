/*
 * Copyright 2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet;

import java.math.BigInteger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import android.os.Parcel;
import android.os.Parcelable;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.WrongNetworkException;
import com.google.bitcoin.uri.BitcoinURI;

import de.schildbach.wallet.util.Bluetooth;
import de.schildbach.wallet.util.GenericUtils;

/**
 * @author Andreas Schildbach
 */
public final class PaymentIntent implements Parcelable
{
	public enum BIP
	{
		BIP21, BIP70
	}

	@CheckForNull
	public final BIP bip;

	@CheckForNull
	public final BigInteger amount;

	private final Address address;

	@CheckForNull
	public final String addressLabel;

	@CheckForNull
	public final String bluetoothMac;

	public PaymentIntent(@Nonnull final Address address)
	{
		this(null, address, null, null, null);
	}

	public PaymentIntent(@Nonnull final String address, @Nullable final String addressLabel) throws WrongNetworkException, AddressFormatException
	{
		this(null, new Address(Constants.NETWORK_PARAMETERS, address), addressLabel, null, null);
	}

	public PaymentIntent(@Nullable final BIP bip, @Nonnull final Address address, @Nullable final String addressLabel,
			@Nullable final BigInteger amount, @Nullable final String bluetoothMac)
	{
		this.bip = bip;
		this.amount = amount;
		this.address = address;
		this.addressLabel = addressLabel;
		this.bluetoothMac = bluetoothMac;
	}

	public PaymentIntent(@Nonnull final BitcoinURI bitcoinUri)
	{
		this(PaymentIntent.BIP.BIP21, bitcoinUri.getAddress(), bitcoinUri.getLabel(), bitcoinUri.getAmount(), (String) bitcoinUri
				.getParameterByName(Bluetooth.MAC_URI_PARAM));
	}

	public String getAddressString()
	{
		return address.toString();
	}

	public boolean hasAmount()
	{
		return amount != null;
	}

	@Override
	public String toString()
	{
		final StringBuilder builder = new StringBuilder();

		builder.append(getClass().getSimpleName());
		builder.append('[');
		builder.append(address.toString());
		builder.append(',');
		builder.append(amount != null ? GenericUtils.formatValue(amount, Constants.BTC_MAX_PRECISION, 0) : "null");
		builder.append(',');
		builder.append(bluetoothMac);
		builder.append(']');

		return builder.toString();
	}

	@Override
	public int describeContents()
	{
		return 0;
	}

	@Override
	public void writeToParcel(final Parcel dest, final int flags)
	{
		dest.writeSerializable(bip);

		dest.writeSerializable(amount);

		dest.writeSerializable(address.getParameters());
		dest.writeByteArray(address.getHash160());

		dest.writeString(addressLabel);

		dest.writeString(bluetoothMac);
	}

	public static final Parcelable.Creator<PaymentIntent> CREATOR = new Parcelable.Creator<PaymentIntent>()
	{
		@Override
		public PaymentIntent createFromParcel(final Parcel in)
		{
			return new PaymentIntent(in);
		}

		@Override
		public PaymentIntent[] newArray(final int size)
		{
			return new PaymentIntent[size];
		}
	};

	private PaymentIntent(final Parcel in)
	{
		bip = (BIP) in.readSerializable();

		amount = (BigInteger) in.readSerializable();

		final NetworkParameters addressParameters = (NetworkParameters) in.readSerializable();
		final byte[] addressHash = new byte[Address.LENGTH];
		in.readByteArray(addressHash);
		address = new Address(addressParameters, addressHash);

		addressLabel = in.readString();

		bluetoothMac = in.readString();
	}
}
