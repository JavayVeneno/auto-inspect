package com.veneno.utils;


import java.security.MessageDigest;

public class MD5Utils {
    public MD5Utils() {
    }

    public static String getMD5(String message) throws Exception {
        String md5str = "";

        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] input = message.getBytes();
            byte[] buff = md.digest(input);
            md5str = bytesToHex(buff);
        } catch (Exception var5) {
            var5.printStackTrace();
        }

        return md5str;
    }

    public static String bytesToHex(byte[] bytes) throws Exception {
        StringBuffer md5str = new StringBuffer();

        for(int i = 0; i < bytes.length; ++i) {
            int digital = bytes[i];
            if (digital < 0) {
                digital += 256;
            }

            if (digital < 16) {
                md5str.append("0");
            }

            md5str.append(Integer.toHexString(digital));
        }

        return md5str.toString().toUpperCase();
    }
}

