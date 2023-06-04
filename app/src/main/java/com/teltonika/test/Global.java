package com.teltonika.test;

import java.util.Calendar;

public class Global {

    public static boolean isWorkingTime() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return hour >= 1 && hour < 5;
    }
}
