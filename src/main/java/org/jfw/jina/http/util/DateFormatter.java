package org.jfw.jina.http.util;

import java.util.BitSet;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

public final class DateFormatter {

	private static final BitSet DELIMITERS = new BitSet();
	static {
		DELIMITERS.set(0x09);
		for (char c = 0x20; c <= 0x2F; c++) {
			DELIMITERS.set(c);
		}
		for (char c = 0x3B; c <= 0x40; c++) {
			DELIMITERS.set(c);
		}
		for (char c = 0x5B; c <= 0x60; c++) {
			DELIMITERS.set(c);
		}
		for (char c = 0x7B; c <= 0x7E; c++) {
			DELIMITERS.set(c);
		}
	}

	private static final String[] DAY_OF_WEEK_TO_SHORT_NAME = new String[] { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };

	private static final String[] CALENDAR_MONTH_TO_SHORT_NAME = new String[] { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov",
			"Dec" };

	/**
	 * Parse some text into a {@link Date}, according to RFC6265
	 * 
	 * @param txt
	 *            text to parse
	 * @return a {@link Date}, or null if text couldn't be parsed
	 */
	public Date parseHttpDate(String txt) {
		return parseHttpDate(txt, 0, txt.length());
	}

	/**
	 * Parse some text into a {@link Date}, according to RFC6265
	 * 
	 * @param txt
	 *            text to parse
	 * @param start
	 *            the start index inside {@code txt}
	 * @param end
	 *            the end index inside {@code txt}
	 * @return a {@link Date}, or null if text couldn't be parsed
	 */
	public Date parseHttpDate(String txt, int start, int end) {
		int length = end - start;
		if (length == 0) {
			return null;
		} else if (length < 0) {
			throw new IllegalArgumentException("Can't have end < start");
		} else if (length > 64) {
			throw new IllegalArgumentException("Can't parse more than 64 chars," + "looks like a user error or a malformed header");
		}
		this.reset();
		boolean allPartsFound = parse1(txt, start, end);
		return allPartsFound && normalizeAndValidate() ? computeDate() : null;
	}

	/**
	 * Format a {@link Date} into RFC1123 format
	 * 
	 * @param date
	 *            the date to format
	 * @return a RFC1123 string
	 */
	public String formatGMT(Date date) {
		this.reset();
		append0(date, sb);
		return sb.toString();
	}

	public String formatGMT(long time) {
		if ((time / 1000) == this.lastHttpHeaderDataValue) {
			return this.lastHttpHeaderDate;
		}
		this.reset();
		append0(time, sb);
		return sb.toString();
	}

	private String lastHttpHeaderDate = null;
	private long lastHttpHeaderDataValue = 0;

	public String httpDateHeaderValue() {
		long v = System.currentTimeMillis();
		long vs = v / 1000;
		if (vs != lastHttpHeaderDataValue) {
			this.lastHttpHeaderDataValue = vs;
			this.reset();
			this.append0(v, sb);
			lastHttpHeaderDate = sb.toString();
		}
		return lastHttpHeaderDate;
	}

	/**
	 * Append a {@link Date} to a {@link StringBuilder} into RFC1123 format
	 * 
	 * @param date
	 *            the date to format
	 * @param sb
	 *            the StringBuilder
	 * @return the same StringBuilder
	 */
	public StringBuilder append(Date date, StringBuilder sb) {
		this.reset();
		return append0(date, sb);
	}

	// delimiter = %x09 / %x20-2F / %x3B-40 / %x5B-60 / %x7B-7E
	private static boolean isDelim(char c) {
		return DELIMITERS.get(c);
	}

	private static boolean isDigit(char c) {
		return c >= 48 && c <= 57;
	}

	private static int getNumericalValue(char c) {
		return c - 48;
	}

	private final GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
	private final StringBuilder sb = new StringBuilder(29); // Sun, 27 Nov 2016
															// 19:37:15 GMT
	private boolean timeFound;
	private int hours;
	private int minutes;
	private int seconds;
	private boolean dayOfMonthFound;
	private int dayOfMonth;
	private boolean monthFound;
	private int month;
	private boolean yearFound;
	private int year;

	public DateFormatter() {
		reset();
	}

	public void reset() {
		timeFound = false;
		hours = -1;
		minutes = -1;
		seconds = -1;
		dayOfMonthFound = false;
		dayOfMonth = -1;
		monthFound = false;
		month = -1;
		yearFound = false;
		year = -1;
		cal.clear();
		sb.setLength(0);
	}

	private boolean tryParseTime(CharSequence txt, int tokenStart, int tokenEnd) {
		int len = tokenEnd - tokenStart;

		// h:m:s to hh:mm:ss
		if (len < 5 || len > 8) {
			return false;
		}

		int localHours = -1;
		int localMinutes = -1;
		int localSeconds = -1;
		int currentPartNumber = 0;
		int currentPartValue = 0;
		int numDigits = 0;

		for (int i = tokenStart; i < tokenEnd; i++) {
			char c = txt.charAt(i);
			if (isDigit(c)) {
				currentPartValue = currentPartValue * 10 + getNumericalValue(c);
				if (++numDigits > 2) {
					return false; // too many digits in this part
				}
			} else if (c == ':') {
				if (numDigits == 0) {
					// no digits between separators
					return false;
				}
				switch (currentPartNumber) {
				case 0:
					// flushing hours
					localHours = currentPartValue;
					break;
				case 1:
					// flushing minutes
					localMinutes = currentPartValue;
					break;
				default:
					// invalid, too many :
					return false;
				}
				currentPartValue = 0;
				currentPartNumber++;
				numDigits = 0;
			} else {
				// invalid char
				return false;
			}
		}

		if (numDigits > 0) {
			// pending seconds
			localSeconds = currentPartValue;
		}

		if (localHours >= 0 && localMinutes >= 0 && localSeconds >= 0) {
			hours = localHours;
			minutes = localMinutes;
			seconds = localSeconds;
			return true;
		}

		return false;
	}

	private boolean tryParseDayOfMonth(CharSequence txt, int tokenStart, int tokenEnd) {
		int len = tokenEnd - tokenStart;

		if (len == 1) {
			char c0 = txt.charAt(tokenStart);
			if (isDigit(c0)) {
				dayOfMonth = getNumericalValue(c0);
				return true;
			}

		} else if (len == 2) {
			char c0 = txt.charAt(tokenStart);
			char c1 = txt.charAt(tokenStart + 1);
			if (isDigit(c0) && isDigit(c1)) {
				dayOfMonth = getNumericalValue(c0) * 10 + getNumericalValue(c1);
				return true;
			}
		}

		return false;
	}

	private static boolean matchMonth(String month, String txt, int tokenStart) {
		return month.regionMatches(true, 0, txt, tokenStart, 3);
	}

	private boolean tryParseMonth(String txt, int tokenStart, int tokenEnd) {
		int len = tokenEnd - tokenStart;

		if (len != 3) {
			return false;
		}

		if (matchMonth("Jan", txt, tokenStart)) {
			month = Calendar.JANUARY;
		} else if (matchMonth("Feb", txt, tokenStart)) {
			month = Calendar.FEBRUARY;
		} else if (matchMonth("Mar", txt, tokenStart)) {
			month = Calendar.MARCH;
		} else if (matchMonth("Apr", txt, tokenStart)) {
			month = Calendar.APRIL;
		} else if (matchMonth("May", txt, tokenStart)) {
			month = Calendar.MAY;
		} else if (matchMonth("Jun", txt, tokenStart)) {
			month = Calendar.JUNE;
		} else if (matchMonth("Jul", txt, tokenStart)) {
			month = Calendar.JULY;
		} else if (matchMonth("Aug", txt, tokenStart)) {
			month = Calendar.AUGUST;
		} else if (matchMonth("Sep", txt, tokenStart)) {
			month = Calendar.SEPTEMBER;
		} else if (matchMonth("Oct", txt, tokenStart)) {
			month = Calendar.OCTOBER;
		} else if (matchMonth("Nov", txt, tokenStart)) {
			month = Calendar.NOVEMBER;
		} else if (matchMonth("Dec", txt, tokenStart)) {
			month = Calendar.DECEMBER;
		} else {
			return false;
		}

		return true;
	}

	private boolean tryParseYear(CharSequence txt, int tokenStart, int tokenEnd) {
		int len = tokenEnd - tokenStart;

		if (len == 2) {
			char c0 = txt.charAt(tokenStart);
			char c1 = txt.charAt(tokenStart + 1);
			if (isDigit(c0) && isDigit(c1)) {
				year = getNumericalValue(c0) * 10 + getNumericalValue(c1);
				return true;
			}

		} else if (len == 4) {
			char c0 = txt.charAt(tokenStart);
			char c1 = txt.charAt(tokenStart + 1);
			char c2 = txt.charAt(tokenStart + 2);
			char c3 = txt.charAt(tokenStart + 3);
			if (isDigit(c0) && isDigit(c1) && isDigit(c2) && isDigit(c3)) {
				year = getNumericalValue(c0) * 1000 + getNumericalValue(c1) * 100 + getNumericalValue(c2) * 10 + getNumericalValue(c3);
				return true;
			}
		}

		return false;
	}

	private boolean parseToken(String txt, int tokenStart, int tokenEnd) {
		// return true if all parts are found
		if (!timeFound) {
			timeFound = tryParseTime(txt, tokenStart, tokenEnd);
			if (timeFound) {
				return dayOfMonthFound && monthFound && yearFound;
			}
		}

		if (!dayOfMonthFound) {
			dayOfMonthFound = tryParseDayOfMonth(txt, tokenStart, tokenEnd);
			if (dayOfMonthFound) {
				return timeFound && monthFound && yearFound;
			}
		}

		if (!monthFound) {
			monthFound = tryParseMonth(txt, tokenStart, tokenEnd);
			if (monthFound) {
				return timeFound && dayOfMonthFound && yearFound;
			}
		}

		if (!yearFound) {
			yearFound = tryParseYear(txt, tokenStart, tokenEnd);
		}
		return timeFound && dayOfMonthFound && monthFound && yearFound;
	}

	// private Date parse0(CharSequence txt, int start, int end) {
	// boolean allPartsFound = parse1(txt, start, end);
	// return allPartsFound && normalizeAndValidate() ? computeDate() : null;
	// }

	private boolean parse1(String txt, int start, int end) {
		// return true if all parts are found
		int tokenStart = -1;

		for (int i = start; i < end; i++) {
			char c = txt.charAt(i);

			if (isDelim(c)) {
				if (tokenStart != -1) {
					// terminate token
					if (parseToken(txt, tokenStart, i)) {
						return true;
					}
					tokenStart = -1;
				}
			} else if (tokenStart == -1) {
				// start new token
				tokenStart = i;
			}
		}

		// terminate trailing token
		return tokenStart != -1 && parseToken(txt, tokenStart, txt.length());
	}

	private boolean normalizeAndValidate() {
		if (dayOfMonth < 1 || dayOfMonth > 31 || hours > 23 || minutes > 59 || seconds > 59) {
			return false;
		}

		if (year >= 70 && year <= 99) {
			year += 1900;
		} else if (year >= 0 && year < 70) {
			year += 2000;
		} else if (year < 1601) {
			// invalid value
			return false;
		}
		return true;
	}

	private Date computeDate() {
		cal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
		cal.set(Calendar.MONTH, month);
		cal.set(Calendar.YEAR, year);
		cal.set(Calendar.HOUR_OF_DAY, hours);
		cal.set(Calendar.MINUTE, minutes);
		cal.set(Calendar.SECOND, seconds);
		return cal.getTime();
	}

	// private String format0(Date date) {
	// append0(date, sb);
	// return sb.toString();
	// }

	private StringBuilder append0(Date date, StringBuilder sb) {
		cal.setTime(date);
		sb.append(DAY_OF_WEEK_TO_SHORT_NAME[cal.get(Calendar.DAY_OF_WEEK) - 1]).append(", ");
		sb.append(cal.get(Calendar.DAY_OF_MONTH)).append(' ');
		sb.append(CALENDAR_MONTH_TO_SHORT_NAME[cal.get(Calendar.MONTH)]).append(' ');
		sb.append(cal.get(Calendar.YEAR)).append(' ');
		appendZeroLeftPadded(cal.get(Calendar.HOUR_OF_DAY), sb).append(':');
		appendZeroLeftPadded(cal.get(Calendar.MINUTE), sb).append(':');
		return appendZeroLeftPadded(cal.get(Calendar.SECOND), sb).append(" GMT");
	}

	private StringBuilder append0(long time, StringBuilder sb) {
		cal.setTimeInMillis(time);
		sb.append(DAY_OF_WEEK_TO_SHORT_NAME[cal.get(Calendar.DAY_OF_WEEK) - 1]).append(", ");
		sb.append(cal.get(Calendar.DAY_OF_MONTH)).append(' ');
		sb.append(CALENDAR_MONTH_TO_SHORT_NAME[cal.get(Calendar.MONTH)]).append(' ');
		sb.append(cal.get(Calendar.YEAR)).append(' ');
		appendZeroLeftPadded(cal.get(Calendar.HOUR_OF_DAY), sb).append(':');
		appendZeroLeftPadded(cal.get(Calendar.MINUTE), sb).append(':');
		return appendZeroLeftPadded(cal.get(Calendar.SECOND), sb).append(" GMT");
	}

	private static StringBuilder appendZeroLeftPadded(int value, StringBuilder sb) {
		if (value < 10) {
			sb.append('0');
		}
		return sb.append(value);
	}

	// public static void main(String[] args) {
	// System.out.println(new DateFormatter().format(new Date()));
	// }
}
