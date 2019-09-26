/*
 * SerialPort_Posix.c
 *
 *       Created on:  Feb 25, 2012
 *  Last Updated on:  Jul 08, 2019
 *           Author:  Will Hedgecock
 *
 * Copyright (C) 2012-2019 Fazecast, Inc.
 *
 * This file is part of jSerialComm.
 *
 * jSerialComm is free software: you can redistribute it and/or modify
 * it under the terms of either the Apache Software License, version 2, or
 * the GNU Lesser General Public License as published by the Free Software
 * Foundation, version 3 or above.
 *
 * jSerialComm is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of both the GNU Lesser General Public
 * License and the Apache Software License along with jSerialComm. If not,
 * see <http://www.gnu.org/licenses/> and <http://www.apache.org/licenses/>.
 */

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <termios.h>
#include <unistd.h>
#if defined(__linux__)
#include <linux/serial.h>
#elif defined(__sun__)
#include <sys/filio.h>
#elif defined(__APPLE__)
#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/IOKitLib.h>
#include <IOKit/serial/IOSerialKeys.h>
#include <IOKit/serial/ioss.h>
#endif
#include "PosixHelperFunctions.h"
#include "../com_fazecast_jSerialComm_SerialPort.h"

// Cached class, method, and field IDs
jclass serialCommClass;
jmethodID serialCommConstructor;
jfieldID serialPortFdField;
jfieldID comPortField;
jfieldID friendlyNameField;
jfieldID portDescriptionField;
jfieldID isOpenedField;
jfieldID disableConfigField;
jfieldID isDtrEnabledField;
jfieldID isRtsEnabledField;
jfieldID baudRateField;
jfieldID dataBitsField;
jfieldID stopBitsField;
jfieldID parityField;
jfieldID flowControlField;
jfieldID sendDeviceQueueSizeField;
jfieldID receiveDeviceQueueSizeField;
jfieldID rs485ModeField;
jfieldID rs485DelayBeforeField;
jfieldID rs485DelayAfterField;
jfieldID timeoutModeField;
jfieldID readTimeoutField;
jfieldID writeTimeoutField;
jfieldID eventFlagsField;

JNIEXPORT jobjectArray JNICALL Java_com_fazecast_jSerialComm_SerialPort_getCommPorts(JNIEnv *env, jclass serialComm)
#if defined(__linux__)
{
	// Enumerate serial ports on machine
	charTupleVector serialPorts = { (char**)malloc(1), (char**)malloc(1), (char**)malloc(1), 0 };
	recursiveSearchForComPorts(&serialPorts, "/sys/devices/");
	lastDitchSearchForComPorts(&serialPorts);
	jobjectArray arrayObject = (*env)->NewObjectArray(env, serialPorts.length, serialCommClass, 0);
	int i;
	for (i = 0; i < serialPorts.length; ++i)
	{
		// Create new SerialComm object containing the enumerated values
		jobject serialCommObject = (*env)->NewObject(env, serialCommClass, serialCommConstructor);
		(*env)->SetObjectField(env, serialCommObject, portDescriptionField, (*env)->NewStringUTF(env, serialPorts.third[i]));
		(*env)->SetObjectField(env, serialCommObject, friendlyNameField, (*env)->NewStringUTF(env, serialPorts.second[i]));
		(*env)->SetObjectField(env, serialCommObject, comPortField, (*env)->NewStringUTF(env, serialPorts.first[i]));
		free(serialPorts.first[i]);
		free(serialPorts.second[i]);
		free(serialPorts.third[i]);

		// Add new SerialComm object to array
		(*env)->SetObjectArrayElement(env, arrayObject, i, serialCommObject);
	}
	free(serialPorts.first);
	free(serialPorts.second);
	free(serialPorts.third);

	return arrayObject;
}
#elif defined(__sun__)
{
	// Enumerate serial ports on machine
	charTupleVector serialPorts = { (char**)malloc(1), (char**)malloc(1), (char**)malloc(1), 0 };
	searchForComPorts(&serialPorts);
	jobjectArray arrayObject = (*env)->NewObjectArray(env, serialPorts.length, serialCommClass, 0);
	int i;
	for (i = 0; i < serialPorts.length; ++i)
	{
		// Create new SerialComm object containing the enumerated values
		jobject serialCommObject = (*env)->NewObject(env, serialCommClass, serialCommConstructor);
		(*env)->SetObjectField(env, serialCommObject, portDescriptionField, (*env)->NewStringUTF(env, serialPorts.third[i]));
		(*env)->SetObjectField(env, serialCommObject, friendlyNameField, (*env)->NewStringUTF(env, serialPorts.second[i]));
		(*env)->SetObjectField(env, serialCommObject, comPortField, (*env)->NewStringUTF(env, serialPorts.first[i]));
		free(serialPorts.first[i]);
		free(serialPorts.second[i]);
		free(serialPorts.third[i]);

		// Add new SerialComm object to array
		(*env)->SetObjectArrayElement(env, arrayObject, i, serialCommObject);
	}
	free(serialPorts.first);
	free(serialPorts.second);
	free(serialPorts.third);

	return arrayObject;
}
#elif defined(__APPLE__)
{
	io_object_t serialPort;
	io_iterator_t serialPortIterator;
	int numValues = 0;
	char friendlyName[1024], comPortCu[1024], comPortTty[1024], portDescription[1024];

	// Enumerate serial ports on machine
	IOServiceGetMatchingServices(kIOMasterPortDefault, IOServiceMatching(kIOSerialBSDServiceValue), &serialPortIterator);
	while ((serialPort = IOIteratorNext(serialPortIterator)))
	{
		++numValues;
		IOObjectRelease(serialPort);
	}
	IOIteratorReset(serialPortIterator);
	jobjectArray arrayObject = (*env)->NewObjectArray(env, numValues*2, serialCommClass, 0);
	for (int i = 0; i < numValues; ++i)
	{
		// Get serial port information
		serialPort = IOIteratorNext(serialPortIterator);
		friendlyName[0] = '\0';
		io_registry_entry_t parent = 0;
		io_registry_entry_t service = serialPort;
		while (service)
		{
			if (IOObjectConformsTo(service, "IOUSBDevice"))
			{
				IORegistryEntryGetName(service, friendlyName);
				break;
			}

			if (IORegistryEntryGetParentEntry(service, kIOServicePlane, &parent) != KERN_SUCCESS)
				break;
			if (service != serialPort)
				IOObjectRelease(service);
			service = parent;
		}
		if (service != serialPort)
			IOObjectRelease(service);

		// Get serial port name and COM value
		if (friendlyName[0] == '\0')
		{
			CFStringRef friendlyNameRef = (CFStringRef)IORegistryEntryCreateCFProperty(serialPort, CFSTR(kIOTTYDeviceKey), kCFAllocatorDefault, 0);
			CFStringGetCString(friendlyNameRef, friendlyName, sizeof(friendlyName), kCFStringEncodingUTF8);
			CFRelease(friendlyNameRef);
		}
		CFStringRef comPortRef = (CFStringRef)IORegistryEntryCreateCFProperty(serialPort, CFSTR(kIOCalloutDeviceKey), kCFAllocatorDefault, 0);
		CFStringGetCString(comPortRef, comPortCu, sizeof(comPortCu), kCFStringEncodingUTF8);
		CFRelease(comPortRef);
		comPortRef = (CFStringRef)IORegistryEntryCreateCFProperty(serialPort, CFSTR(kIODialinDeviceKey), kCFAllocatorDefault, 0);
		CFStringGetCString(comPortRef, comPortTty, sizeof(comPortTty), kCFStringEncodingUTF8);
		CFRelease(comPortRef);

		// Create new SerialComm callout object containing the enumerated values and add to array
		jobject serialCommObject = (*env)->NewObject(env, serialCommClass, serialCommConstructor);
		(*env)->SetObjectField(env, serialCommObject, portDescriptionField, (*env)->NewStringUTF(env, friendlyName));
		(*env)->SetObjectField(env, serialCommObject, friendlyNameField, (*env)->NewStringUTF(env, friendlyName));
		(*env)->SetObjectField(env, serialCommObject, comPortField, (*env)->NewStringUTF(env, comPortCu));
		(*env)->SetObjectArrayElement(env, arrayObject, i*2, serialCommObject);
		(*env)->DeleteLocalRef(env, serialCommObject);

		// Create new SerialComm dialin object containing the enumerated values and add to array
		strcat(friendlyName, " (Dial-In)");
		serialCommObject = (*env)->NewObject(env, serialCommClass, serialCommConstructor);
		(*env)->SetObjectField(env, serialCommObject, portDescriptionField, (*env)->NewStringUTF(env, friendlyName));
		(*env)->SetObjectField(env, serialCommObject, friendlyNameField, (*env)->NewStringUTF(env, friendlyName));
		(*env)->SetObjectField(env, serialCommObject, comPortField, (*env)->NewStringUTF(env, comPortTty));
		(*env)->SetObjectArrayElement(env, arrayObject, i*2 + 1, serialCommObject);
		(*env)->DeleteLocalRef(env, serialCommObject);
		IOObjectRelease(serialPort);
	}
	IOObjectRelease(serialPortIterator);

	return arrayObject;
}
#endif

JNIEXPORT void JNICALL Java_com_fazecast_jSerialComm_SerialPort_initializeLibrary(JNIEnv *env, jclass serialComm)
{
	// Cache class and method ID as global references
	serialCommClass = (jclass)(*env)->NewGlobalRef(env, serialComm);
	serialCommConstructor = (*env)->GetMethodID(env, serialCommClass, "<init>", "()V");

	// Cache
	serialPortFdField = (*env)->GetFieldID(env, serialCommClass, "portHandle", "J");
	comPortField = (*env)->GetFieldID(env, serialCommClass, "comPort", "Ljava/lang/String;");
	friendlyNameField = (*env)->GetFieldID(env, serialCommClass, "friendlyName", "Ljava/lang/String;");
	portDescriptionField = (*env)->GetFieldID(env, serialCommClass, "portDescription", "Ljava/lang/String;");
	isOpenedField = (*env)->GetFieldID(env, serialCommClass, "isOpened", "Z");
	disableConfigField = (*env)->GetFieldID(env, serialCommClass, "disableConfig", "Z");
	isDtrEnabledField = (*env)->GetFieldID(env, serialCommClass, "isDtrEnabled", "Z");
	isRtsEnabledField = (*env)->GetFieldID(env, serialCommClass, "isRtsEnabled", "Z");
	baudRateField = (*env)->GetFieldID(env, serialCommClass, "baudRate", "I");
	dataBitsField = (*env)->GetFieldID(env, serialCommClass, "dataBits", "I");
	stopBitsField = (*env)->GetFieldID(env, serialCommClass, "stopBits", "I");
	parityField = (*env)->GetFieldID(env, serialCommClass, "parity", "I");
	flowControlField = (*env)->GetFieldID(env, serialCommClass, "flowControl", "I");
	sendDeviceQueueSizeField = (*env)->GetFieldID(env, serialCommClass, "sendDeviceQueueSize", "I");
	receiveDeviceQueueSizeField = (*env)->GetFieldID(env, serialCommClass, "receiveDeviceQueueSize", "I");
	rs485ModeField = (*env)->GetFieldID(env, serialCommClass, "rs485Mode", "Z");
	rs485DelayBeforeField = (*env)->GetFieldID(env, serialCommClass, "rs485DelayBefore", "I");
	rs485DelayAfterField = (*env)->GetFieldID(env, serialCommClass, "rs485DelayAfter", "I");
	timeoutModeField = (*env)->GetFieldID(env, serialCommClass, "timeoutMode", "I");
	readTimeoutField = (*env)->GetFieldID(env, serialCommClass, "readTimeout", "I");
	writeTimeoutField = (*env)->GetFieldID(env, serialCommClass, "writeTimeout", "I");
	eventFlagsField = (*env)->GetFieldID(env, serialCommClass, "eventFlags", "I");
}

JNIEXPORT void JNICALL Java_com_fazecast_jSerialComm_SerialPort_uninitializeLibrary(JNIEnv *env, jclass serialComm)
{
	// Delete the cache global reference
	(*env)->DeleteGlobalRef(env, serialCommClass);
}

JNIEXPORT jlong JNICALL Java_com_fazecast_jSerialComm_SerialPort_openPortNative(JNIEnv *env, jobject obj)
{
	jstring portNameJString = (jstring)(*env)->GetObjectField(env, obj, comPortField);
	const char *portName = (*env)->GetStringUTFChars(env, portNameJString, NULL);
	unsigned char isDtrEnabled = (*env)->GetBooleanField(env, obj, isDtrEnabledField);
	unsigned char isRtsEnabled = (*env)->GetBooleanField(env, obj, isRtsEnabledField);

	// Try to open existing serial port with read/write access
	int serialPortFD = -1;
	if ((serialPortFD = open(portName, O_RDWR | O_NOCTTY | O_NONBLOCK)) > 0)
	{
		// Ensure that multiple root users cannot access the device simultaneously
		if (flock(serialPortFD, LOCK_EX | LOCK_NB) == -1)
		{
			while ((close(serialPortFD) == -1) && (errno != EBADF));
			serialPortFD = -1;
		}
		else
		{
			// Clear any serial port flags and set up raw, non-canonical port parameters
			struct termios options = { 0 };
			fcntl(serialPortFD, F_SETFL, 0);
			tcgetattr(serialPortFD, &options);
#if defined(__sun__)
			options.c_iflag &= ~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR | ICRNL | IXON);
			options.c_oflag &= ~OPOST;
			options.c_lflag &= ~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);
			options.c_cflag &= ~(CSIZE | PARENB);
			options.c_cflag |= CS8;
#else
			cfmakeraw(&options);
#endif
			if (!isDtrEnabled || !isRtsEnabled)
				options.c_cflag &= ~HUPCL;
			options.c_iflag |= BRKINT;
			tcsetattr(serialPortFD, TCSANOW, &options);

			// Configure the port parameters and timeouts
			if (Java_com_fazecast_jSerialComm_SerialPort_configPort(env, obj, serialPortFD))
				(*env)->SetBooleanField(env, obj, isOpenedField, JNI_TRUE);
			else
			{
				// Close the port if there was a problem setting the parameters
				ioctl(serialPortFD, TIOCNXCL);
				tcdrain(serialPortFD);
				while ((close(serialPortFD) == -1) && (errno != EBADF));
				serialPortFD = -1;
				(*env)->SetBooleanField(env, obj, isOpenedField, JNI_FALSE);
			}
		}
	}

	(*env)->ReleaseStringUTFChars(env, portNameJString, portName);
	return serialPortFD;
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_configPort(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;
	struct termios options = { 0 };

	// Get port parameters from Java class
	baud_rate baudRate = (*env)->GetIntField(env, obj, baudRateField);
	int byteSizeInt = (*env)->GetIntField(env, obj, dataBitsField);
	int stopBitsInt = (*env)->GetIntField(env, obj, stopBitsField);
	int parityInt = (*env)->GetIntField(env, obj, parityField);
	int flowControl = (*env)->GetIntField(env, obj, flowControlField);
	int sendDeviceQueueSize = (*env)->GetIntField(env, obj, sendDeviceQueueSizeField);
	int receiveDeviceQueueSize = (*env)->GetIntField(env, obj, receiveDeviceQueueSizeField);
	int rs485DelayBefore = (*env)->GetIntField(env, obj, rs485DelayBeforeField);
	int rs485DelayAfter = (*env)->GetIntField(env, obj, rs485DelayAfterField);
	unsigned char configDisabled = (*env)->GetBooleanField(env, obj, disableConfigField);
	unsigned char rs485ModeEnabled = (*env)->GetBooleanField(env, obj, rs485ModeField);
	unsigned char isDtrEnabled = (*env)->GetBooleanField(env, obj, isDtrEnabledField);
	unsigned char isRtsEnabled = (*env)->GetBooleanField(env, obj, isRtsEnabledField);
	tcflag_t byteSize = (byteSizeInt == 5) ? CS5 : (byteSizeInt == 6) ? CS6 : (byteSizeInt == 7) ? CS7 : CS8;
	tcflag_t stopBits = ((stopBitsInt == com_fazecast_jSerialComm_SerialPort_ONE_STOP_BIT) || (stopBitsInt == com_fazecast_jSerialComm_SerialPort_ONE_POINT_FIVE_STOP_BITS)) ? 0 : CSTOPB;
	tcflag_t parity = (parityInt == com_fazecast_jSerialComm_SerialPort_NO_PARITY) ? 0 : (parityInt == com_fazecast_jSerialComm_SerialPort_ODD_PARITY) ? (PARENB | PARODD) : (parityInt == com_fazecast_jSerialComm_SerialPort_EVEN_PARITY) ? PARENB : (parityInt == com_fazecast_jSerialComm_SerialPort_MARK_PARITY) ? (PARENB | CMSPAR | PARODD) : (PARENB | CMSPAR);
	tcflag_t CTSRTSEnabled = (((flowControl & com_fazecast_jSerialComm_SerialPort_FLOW_CONTROL_CTS_ENABLED) > 0) ||
			((flowControl & com_fazecast_jSerialComm_SerialPort_FLOW_CONTROL_RTS_ENABLED) > 0)) ? CRTSCTS : 0;
	tcflag_t XonXoffInEnabled = ((flowControl & com_fazecast_jSerialComm_SerialPort_FLOW_CONTROL_XONXOFF_IN_ENABLED) > 0) ? IXOFF : 0;
	tcflag_t XonXoffOutEnabled = ((flowControl & com_fazecast_jSerialComm_SerialPort_FLOW_CONTROL_XONXOFF_OUT_ENABLED) > 0) ? IXON : 0;

	// Set updated port parameters
	tcgetattr(serialPortFD, &options);
	options.c_cflag = (byteSize | stopBits | parity | CLOCAL | CREAD | CTSRTSEnabled);
	if (parityInt == com_fazecast_jSerialComm_SerialPort_SPACE_PARITY)
		options.c_cflag &= ~PARODD;
	if (!isDtrEnabled || !isRtsEnabled)
		options.c_cflag &= ~HUPCL;
	options.c_iflag &= ~(INPCK | IGNPAR | PARMRK | ISTRIP);
	if (byteSizeInt < 8)
		options.c_iflag |= ISTRIP;
	if (parityInt != 0)
		options.c_iflag |= (INPCK | IGNPAR);
	options.c_iflag |= (XonXoffInEnabled | XonXoffOutEnabled);

	// Set baud rate
	baud_rate baudRateCode = getBaudRateCode(baudRate);
	unsigned char nonStandardBaudRate = (baudRateCode == 0);
	if (nonStandardBaudRate)
		baudRateCode = B38400;
	cfsetispeed(&options, baudRateCode);
	cfsetospeed(&options, baudRateCode);

	// Apply changes and block non-root users from opening this port
	int retVal = configDisabled ? 0 : tcsetattr(serialPortFD, TCSANOW, &options);
	ioctl(serialPortFD, TIOCEXCL);

	// Attempt to set the transmit buffer size and any necessary custom baud rates
#if defined(__linux__)
	struct serial_struct serInfo;
	if (ioctl(serialPortFD, TIOCGSERIAL, &serInfo) == 0)
	{
		serInfo.xmit_fifo_size = sendDeviceQueueSize;
		ioctl(serialPortFD, TIOCSSERIAL, &serInfo);
	}
#endif
	if (nonStandardBaudRate)
		setBaudRateCustom(serialPortFD, baudRate);

	// Attempt to set the requested RS-485 mode
#if defined(__linux__)
	struct serial_rs485 rs485Conf;
	if (ioctl(serialPortFD, TIOCGRS485, &rs485Conf) == 0)
	{
		if (rs485ModeEnabled)
			rs485Conf.flags |= SER_RS485_ENABLED;
		else
			rs485Conf.flags &= ~SER_RS485_ENABLED;
		rs485Conf.delay_rts_before_send = rs485DelayBefore;
		rs485Conf.delay_rts_after_send = rs485DelayAfter;
		ioctl(serialPortFD, TIOCSRS485, &rs485Conf);
	}
#endif
	return ((retVal == 0) && Java_com_fazecast_jSerialComm_SerialPort_configEventFlags(env, obj, serialPortFD) ? JNI_TRUE : JNI_FALSE);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_configTimeouts(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	// Get port timeouts from Java class
	if (serialPortFD <= 0)
		return JNI_FALSE;
	baud_rate baudRate = (*env)->GetIntField(env, obj, baudRateField);
	baud_rate baudRateCode = getBaudRateCode(baudRate);
	int timeoutMode = (*env)->GetIntField(env, obj, timeoutModeField);
	int readTimeout = (*env)->GetIntField(env, obj, readTimeoutField);

	// Retrieve existing port configuration
	struct termios options = { 0 };
	tcgetattr(serialPortFD, &options);
	int flags = fcntl(serialPortFD, F_GETFL);
	if (flags == -1)
		return JNI_FALSE;

	// Set updated port timeouts
	if (((timeoutMode & com_fazecast_jSerialComm_SerialPort_TIMEOUT_READ_SEMI_BLOCKING) > 0) && (readTimeout > 0))	// Read Semi-blocking with timeout
	{
		flags &= ~O_NONBLOCK;
		options.c_cc[VMIN] = 0;
		options.c_cc[VTIME] = readTimeout / 100;
	}
	else if ((timeoutMode & com_fazecast_jSerialComm_SerialPort_TIMEOUT_READ_SEMI_BLOCKING) > 0)					// Read Semi-blocking without timeout
	{
		flags &= ~O_NONBLOCK;
		options.c_cc[VMIN] = 1;
		options.c_cc[VTIME] = 0;
	}
	else if (((timeoutMode & com_fazecast_jSerialComm_SerialPort_TIMEOUT_READ_BLOCKING) > 0)  && (readTimeout > 0))	// Read Blocking with timeout
	{
		flags &= ~O_NONBLOCK;
		options.c_cc[VMIN] = 0;
		options.c_cc[VTIME] = readTimeout / 100;
	}
	else if ((timeoutMode & com_fazecast_jSerialComm_SerialPort_TIMEOUT_READ_BLOCKING) > 0)							// Read Blocking without timeout
	{
		flags &= ~O_NONBLOCK;
		options.c_cc[VMIN] = 1;
		options.c_cc[VTIME] = 0;
	}
	else if ((timeoutMode & com_fazecast_jSerialComm_SerialPort_TIMEOUT_SCANNER) > 0)								// Scanner Mode
	{
		flags &= ~O_NONBLOCK;
		options.c_cc[VMIN] = 1;
		options.c_cc[VTIME] = 1;
	}
	else																											// Non-blocking
	{
		flags |= O_NONBLOCK;
		options.c_cc[VMIN] = 0;
		options.c_cc[VTIME] = 0;
	}

	// Apply changes
	int retVal = fcntl(serialPortFD, F_SETFL, flags);
	if (retVal != -1)
		retVal = tcsetattr(serialPortFD, TCSANOW, &options);
	if (baudRateCode == 0)
		setBaudRateCustom(serialPortFD, baudRate);
	return ((retVal == 0) ? JNI_TRUE : JNI_FALSE);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_configEventFlags(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;

	// Get event flags from Java class
	baud_rate baudRate = (*env)->GetIntField(env, obj, baudRateField);
	baud_rate baudRateCode = getBaudRateCode(baudRate);
	int eventsToMonitor = (*env)->GetIntField(env, obj, eventFlagsField);

	// Change read timeouts if we are monitoring data received
	jboolean retVal;
	if ((eventsToMonitor & com_fazecast_jSerialComm_SerialPort_LISTENING_EVENT_DATA_RECEIVED) > 0)
	{
		struct termios options = { 0 };
		tcgetattr(serialPortFD, &options);
		int flags = fcntl(serialPortFD, F_GETFL);
		if (flags == -1)
			return JNI_FALSE;
		flags &= ~O_NONBLOCK;
		options.c_cc[VMIN] = 0;
		options.c_cc[VTIME] = 10;
		retVal = ((fcntl(serialPortFD, F_SETFL, flags) == -1) || (tcsetattr(serialPortFD, TCSANOW, &options) == -1)) ?
				JNI_FALSE : JNI_TRUE;
		if (baudRateCode == 0)
			setBaudRateCustom(serialPortFD, baudRate);
	}
	else
		retVal = Java_com_fazecast_jSerialComm_SerialPort_configTimeouts(env, obj, serialPortFD);

	// Apply changes
	return retVal;
}

JNIEXPORT jint JNICALL Java_com_fazecast_jSerialComm_SerialPort_waitForEvent(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	// Initialize the waiting set
	if (serialPortFD <= 0)
		return 0;
	struct pollfd waitingSet = { serialPortFD, POLLIN, 0 };

	// Wait for a serial port event
	if (poll(&waitingSet, 1, 1000) <= 0)
		return 0;
	return (waitingSet.revents & POLLIN) ? com_fazecast_jSerialComm_SerialPort_LISTENING_EVENT_DATA_AVAILABLE : 0;
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_closePortNative(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	// Ensure that the port is open
	if (serialPortFD <= 0)
		return JNI_TRUE;

	// Allow others to open the port
	ioctl(serialPortFD, TIOCNXCL);
	tcdrain(serialPortFD);
	(*env)->SetBooleanField(env, obj, isOpenedField, JNI_FALSE);

	// Force the port to enter non-blocking mode to ensure that any current reads return
	struct termios options;
	tcgetattr(serialPortFD, &options);
	int flags = fcntl(serialPortFD, F_GETFL);
	flags |= O_NONBLOCK;
	options.c_cc[VMIN] = 0;
	options.c_cc[VTIME] = 0;
	int retVal = fcntl(serialPortFD, F_SETFL, flags);
	tcsetattr(serialPortFD, TCSANOW, &options);

	// Close the port
	flock(serialPortFD, LOCK_UN);
	while ((close(serialPortFD) == -1) && (errno != EBADF));
	(*env)->SetLongField(env, obj, serialPortFdField, -1l);
	return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_com_fazecast_jSerialComm_SerialPort_bytesAvailable(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	int numBytesAvailable = -1;
	if (serialPortFD > 0)
		ioctl(serialPortFD, FIONREAD, &numBytesAvailable);

	return numBytesAvailable;
}

JNIEXPORT jint JNICALL Java_com_fazecast_jSerialComm_SerialPort_bytesAwaitingWrite(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	int numBytesToWrite = -1;
	if (serialPortFD > 0)
		ioctl(serialPortFD, TIOCOUTQ, &numBytesToWrite);

	return numBytesToWrite;
}

JNIEXPORT jint JNICALL Java_com_fazecast_jSerialComm_SerialPort_readBytes(JNIEnv *env, jobject obj, jlong serialPortFD, jbyteArray buffer, jlong bytesToRead, jlong offset)
{
	// Get port handle and read timeout from Java class
	if (serialPortFD <= 0)
		return -1;
	int timeoutMode = (*env)->GetIntField(env, obj, timeoutModeField);
	int readTimeout = (*env)->GetIntField(env, obj, readTimeoutField);
	int numBytesRead, numBytesReadTotal = 0, bytesRemaining = bytesToRead;
	char* readBuffer = (char*)malloc(bytesToRead);

	// Infinite blocking mode specified, don't return until we have completely finished the read
	if (((timeoutMode & com_fazecast_jSerialComm_SerialPort_TIMEOUT_READ_BLOCKING) > 0) && (readTimeout == 0))
	{
		// While there are more bytes we are supposed to read
		while (bytesRemaining > 0)
		{
			do { numBytesRead = read(serialPortFD, readBuffer+numBytesReadTotal, bytesRemaining); } while ((numBytesRead < 0) && (errno == EINTR));
			if (numBytesRead == -1)
			{
				// Problem reading, allow others to open the port and close it ourselves
				ioctl(serialPortFD, TIOCNXCL);
				tcdrain(serialPortFD);
				while (((*env)->GetBooleanField(env, obj, isOpenedField)) && (close(serialPortFD) == -1) && (errno != EBADF));
				serialPortFD = -1;
				(*env)->SetLongField(env, obj, serialPortFdField, -1l);
				(*env)->SetBooleanField(env, obj, isOpenedField, JNI_FALSE);
				break;
			}

			// Fix index variables
			numBytesReadTotal += numBytesRead;
			bytesRemaining -= numBytesRead;
		}
	}
	else if ((timeoutMode & com_fazecast_jSerialComm_SerialPort_TIMEOUT_READ_BLOCKING) > 0)		// Blocking mode, but not indefinitely
	{
		// Get current system time
		struct timeval expireTime = { 0 }, currTime = { 0 };
		gettimeofday(&expireTime, NULL);
		expireTime.tv_usec += (readTimeout * 1000);
		if (expireTime.tv_usec > 1000000)
		{
			expireTime.tv_sec += (expireTime.tv_usec * 0.000001);
			expireTime.tv_usec = (expireTime.tv_usec % 1000000);
		}

		// While there are more bytes we are supposed to read and the timeout has not elapsed
		do
		{
			do { numBytesRead = read(serialPortFD, readBuffer+numBytesReadTotal, bytesRemaining); } while ((numBytesRead < 0) && (errno == EINTR));
			if (numBytesRead == -1)
			{
				// Problem reading, allow others to open the port and close it ourselves
				ioctl(serialPortFD, TIOCNXCL);
				tcdrain(serialPortFD);
				while (((*env)->GetBooleanField(env, obj, isOpenedField)) && (close(serialPortFD) == -1) && (errno != EBADF));
				serialPortFD = -1;
				(*env)->SetLongField(env, obj, serialPortFdField, -1l);
				(*env)->SetBooleanField(env, obj, isOpenedField, JNI_FALSE);
				break;
			}

			// Fix index variables
			numBytesReadTotal += numBytesRead;
			bytesRemaining -= numBytesRead;

			// Get current system time
			gettimeofday(&currTime, NULL);
		} while ((bytesRemaining > 0) && ((expireTime.tv_sec > currTime.tv_sec) ||
				((expireTime.tv_sec == currTime.tv_sec) && (expireTime.tv_usec > currTime.tv_usec))));
	}
	else		// Semi- or non-blocking specified
	{
		// Read from port
		do { numBytesRead = read(serialPortFD, readBuffer, bytesToRead); } while ((numBytesRead < 0) && (errno == EINTR));
		if (numBytesRead == -1)
		{
			// Problem reading, allow others to open the port and close it ourselves
			ioctl(serialPortFD, TIOCNXCL);
			tcdrain(serialPortFD);
			while (((*env)->GetBooleanField(env, obj, isOpenedField)) && (close(serialPortFD) == -1) && (errno != EBADF));
			serialPortFD = -1;
			(*env)->SetLongField(env, obj, serialPortFdField, -1l);
			(*env)->SetBooleanField(env, obj, isOpenedField, JNI_FALSE);
		}
		else
			numBytesReadTotal = numBytesRead;
	}

	// Return number of bytes read if successful
	(*env)->SetByteArrayRegion(env, buffer, offset, numBytesReadTotal, (jbyte*)readBuffer);
	free(readBuffer);
	return (numBytesRead == -1) || !((*env)->GetBooleanField(env, obj, isOpenedField)) ? -1 : numBytesReadTotal;
}

JNIEXPORT jint JNICALL Java_com_fazecast_jSerialComm_SerialPort_writeBytes(JNIEnv *env, jobject obj, jlong serialPortFD, jbyteArray buffer, jlong bytesToWrite, jlong offset)
{
	if (serialPortFD <= 0)
		return -1;
	int timeoutMode = (*env)->GetIntField(env, obj, timeoutModeField);
	jbyte *writeBuffer = (*env)->GetByteArrayElements(env, buffer, 0);
	int numBytesWritten, result = 0;

	// Set the DTR line to high if using RS-422
	//ioctl(serialPortFD, TIOCMGET, &result);
	//result |= TIOCM_DTR;
	//ioctl(serialPortFD, TIOCMSET, &result);

	// Write to port
	do { numBytesWritten = write(serialPortFD, writeBuffer+offset, bytesToWrite); } while ((numBytesWritten < 0) && (errno == EINTR));
	if (numBytesWritten == -1)
	{
		// Problem writing, allow others to open the port and close it ourselves
		ioctl(serialPortFD, TIOCNXCL);
		tcdrain(serialPortFD);
		while (((*env)->GetBooleanField(env, obj, isOpenedField)) && (close(serialPortFD) == -1) && (errno != EBADF));
		serialPortFD = -1;
		(*env)->SetLongField(env, obj, serialPortFdField, -1l);
		(*env)->SetBooleanField(env, obj, isOpenedField, JNI_FALSE);
	}

	// Wait until all bytes were written in write-blocking mode
	if ((timeoutMode & com_fazecast_jSerialComm_SerialPort_TIMEOUT_WRITE_BLOCKING) > 0)
		tcdrain(serialPortFD);

	// Clear the DTR line if using RS-422
//#ifdef TIOCSERGETLSR
	//do
	//{
		//result = ioctl(serialPortFD, TIOCSERGETLSR);
		//if (result != TIOCSER_TEMT)
			//usleep(100);
	//} while (result != TIOCSER_TEMT);
//#endif
	//ioctl(serialPortFD, TIOCMGET, &result);
	//result &= ~TIOCM_DTR;
	//ioctl(serialPortFD, TIOCMSET, &result);
	//do { result = tcflush(serialPortFD, TCIFLUSH); } while ((result < 0) && (errno == EINTR));

	// Return number of bytes written if successful
	(*env)->ReleaseByteArrayElements(env, buffer, writeBuffer, JNI_ABORT);
	return numBytesWritten;
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_setBreak(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;
	return (ioctl(serialPortFD, TIOCSBRK) == 0);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_clearBreak(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;
	return (ioctl(serialPortFD, TIOCCBRK) == 0);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_setRTS(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;
	int modemBits = TIOCM_RTS;
	return (ioctl(serialPortFD, TIOCMBIS, &modemBits) == 0);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_clearRTS(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;
	int modemBits = TIOCM_RTS;
	return (ioctl(serialPortFD, TIOCMBIC, &modemBits) == 0);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_presetRTS(JNIEnv *env, jobject obj)
{
	jstring portNameJString = (jstring)(*env)->GetObjectField(env, obj, comPortField);
	const char *portName = (*env)->GetStringUTFChars(env, portNameJString, NULL);

	// Send a system command to preset the RTS mode of the serial port
	char commandString[64];
#if defined(__linux__)
	sprintf(commandString, "stty -F %s hupcl", portName);
#else
	sprintf(commandString, "stty -f %s hupcl", portName);
#endif
	int result = system(commandString);

	(*env)->ReleaseStringUTFChars(env, portNameJString, portName);
	return (result == 0);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_preclearRTS(JNIEnv *env, jobject obj)
{
	jstring portNameJString = (jstring)(*env)->GetObjectField(env, obj, comPortField);
	const char *portName = (*env)->GetStringUTFChars(env, portNameJString, NULL);

	// Send a system command to preset the RTS mode of the serial port
	char commandString[64];
#if defined(__linux__)
	sprintf(commandString, "stty -F %s -hupcl", portName);
#else
	sprintf(commandString, "stty -f %s -hupcl", portName);
#endif
	int result = system(commandString);

	(*env)->ReleaseStringUTFChars(env, portNameJString, portName);
	return (result == 0);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_setDTR(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;
	int modemBits = TIOCM_DTR;
	return (ioctl(serialPortFD, TIOCMBIS, &modemBits) == 0);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_clearDTR(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;
	int modemBits = TIOCM_DTR;
	return (ioctl(serialPortFD, TIOCMBIC, &modemBits) == 0);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_presetDTR(JNIEnv *env, jobject obj)
{
	jstring portNameJString = (jstring)(*env)->GetObjectField(env, obj, comPortField);
	const char *portName = (*env)->GetStringUTFChars(env, portNameJString, NULL);

	// Send a system command to preset the DTR mode of the serial port
	char commandString[64];
#if defined(__linux__)
	sprintf(commandString, "stty -F %s hupcl", portName);
#else
	sprintf(commandString, "stty -f %s hupcl", portName);
#endif
	int result = system(commandString);

	(*env)->ReleaseStringUTFChars(env, portNameJString, portName);
	return (result == 0);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_preclearDTR(JNIEnv *env, jobject obj)
{
	jstring portNameJString = (jstring)(*env)->GetObjectField(env, obj, comPortField);
	const char *portName = (*env)->GetStringUTFChars(env, portNameJString, NULL);

	// Send a system command to preset the DTR mode of the serial port
	char commandString[64];
#if defined(__linux__)
	sprintf(commandString, "stty -F %s -hupcl", portName);
#else
	sprintf(commandString, "stty -f %s -hupcl", portName);
#endif
	int result = system(commandString);

	(*env)->ReleaseStringUTFChars(env, portNameJString, portName);
	return (result == 0);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_getCTS(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;
	int modemBits = 0;
	return (ioctl(serialPortFD, TIOCMGET, &modemBits) == 0) && (modemBits & TIOCM_CTS);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_getDSR(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;
	int modemBits = 0;
	return (ioctl(serialPortFD, TIOCMGET, &modemBits) == 0) && (modemBits & TIOCM_DSR);
}

JNIEXPORT jboolean JNICALL Java_com_fazecast_jSerialComm_SerialPort_getDCD(JNIEnv *env, jobject obj, jlong serialPortFD)
{
	if (serialPortFD <= 0)
		return JNI_FALSE;
	int modemBits = 0;
	return (ioctl(serialPortFD, TIOCMGET, &modemBits) == 0) && (modemBits & TIOCM_CAR);
}
