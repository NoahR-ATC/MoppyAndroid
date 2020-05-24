package com.moppyandroid.main.service;

import android.media.midi.MidiDeviceInfo;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Duplicate of {@link android.media.midi.MidiDeviceInfo.PortInfo} that provides the parent {@link MidiDeviceInfo}
 * and is {@link android.os.Parcelable}.
 */
public class MidiPortInfoWrapper implements Parcelable {
    private MidiDeviceInfo parent;
    private int portType;
    private int portNumber;
    private String portName;

    /**
     * Constructs a {@code MidiPortInfoWrapper} to represent a MIDI port.
     *
     * @param portInfo the {@link android.media.midi.MidiDeviceInfo.PortInfo} to provide
     * @param parent   the {@link MidiDeviceInfo} that contains {@code portInfo}
     */
    public MidiPortInfoWrapper(MidiDeviceInfo.PortInfo portInfo, MidiDeviceInfo parent) {
        this.parent = parent;
        // Copy attributes of portInfo
        if (portInfo != null) {
            portType = portInfo.getType();
            portNumber = portInfo.getPortNumber();
            portName = portInfo.getName();
        }
    } // End MidiPortInfoWrapper(PortInfo, MidiDeviceInfo) constructor

    /**
     * Gets the type of this port, either {@link android.media.midi.MidiDeviceInfo.PortInfo#TYPE_INPUT}
     * or {@link android.media.midi.MidiDeviceInfo.PortInfo#TYPE_OUTPUT} depending on if this MIDI port
     * receives or sends MIDI messages.
     *
     * @return {@link android.media.midi.MidiDeviceInfo.PortInfo#TYPE_INPUT} or {@link android.media.midi.MidiDeviceInfo.PortInfo#TYPE_OUTPUT}
     */
    public int getType() { return portType; }

    /**
     * Gets the number of this port within either the input or output port lists of the parent
     * {@link MidiDeviceInfo} (i.e. an input port and output port could both have a port number of 0).
     * Not suitable to be used as an index in {@code getParent().{@link MidiDeviceInfo#getPorts() getPorts()}},
     * see {@link #getPortIndex()} for that.
     *
     * @return this port's number
     */
    public int getPortNumber() { return portNumber; }

    /**
     * Gets the index of this port in the array returned with the parent {@link MidiDeviceInfo}'s
     * {@link MidiDeviceInfo#getPorts() getPorts()} method.
     *
     * @return this port's index
     */
    public int getPortIndex() {
        if (portType == MidiDeviceInfo.PortInfo.TYPE_INPUT) {
            return getPortNumber();
        }
        else { return parent.getInputPortCount() + portNumber; }
    } // End getPortIndex method

    /**
     * Gets the name of this port.
     *
     * @return this port's name
     */
    public String getName() { return portName; }

    /**
     * Gets the parent {@link MidiDeviceInfo} of this port.
     *
     * @return this port's parent
     */
    public MidiDeviceInfo getParent() { return parent; }

    /**
     * Gets the name of the parent {@link MidiDeviceInfo} of this port.
     *
     * @return the port's parent's name
     */
    public String getParentName() { return parent.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME); }


    // Parcelable implementation

    /**
     * Creates a new {@code MidiPortInfoWrapper} from a {@link Parcel}.
     *
     * @param in the parcel to read
     */
    protected MidiPortInfoWrapper(Parcel in) {
        parent = in.readParcelable(MidiDeviceInfo.class.getClassLoader());
        portType = in.readInt();
        portNumber = in.readInt();
        portName = in.readString();
    } // End MidiPortInfoWrapper(Parcel) constructor

    /**
     * Writes this {@code MidiPortInfoWrapper} to a {@link Parcel}.
     *
     * @param dest  the parcel to write to
     * @param flags the flags used for writing
     * @see Parcelable#writeToParcel(Parcel, int)
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(parent, flags);
        dest.writeInt(portType);
        dest.writeInt(portNumber);
        dest.writeString(portName);
    } // End writeToParcel method

    /**
     * Returns {@code 0}.
     *
     * @return {@code 0}
     * @see Parcelable#describeContents()
     */
    @Override
    public int describeContents() { return 0; }

    public static final Creator<MidiPortInfoWrapper> CREATOR = new Creator<MidiPortInfoWrapper>() {
        @Override
        public MidiPortInfoWrapper createFromParcel(Parcel in) { return new MidiPortInfoWrapper(in); }

        @Override
        public MidiPortInfoWrapper[] newArray(int size) { return new MidiPortInfoWrapper[size]; }
    };
} // End MidiInfoPortWrapper class
