package org.example.proxy;

public class Utils {

    /**
     * CRC-8 校验（多项式 0x07）?
     * 根据指定的数据和指定长度生成crc
     */
    private static byte getCRC(byte[] data, int size) {
        if (data == null) return 0;
        int crc = 0x00; // 用 int 防止溢出
        for (int i = 0; i < size; i++) {
            crc ^= (data[i] & 0xFF);
            for (int a = 0; a < 8; a++) {
                if ((crc & 0x80) != 0) crc = ((crc << 1) ^ 0x07) & 0xFF;
                else crc = (crc << 1) & 0xFF;
            }
        }
        return (byte) crc;
    }

    /**
     * 根据给指定的数据生成crc
     */
    public static byte getCRC(byte[] data) {
        return getCRC(data, data.length);
    }

    /**
     * 判断
     */
    public static boolean checkCRC(byte[] data) {
        byte reCRC = getCRC(data, data.length - 1);
        byte crc = data[data.length - 1];//数据的最后一位是crc
        if (crc == reCRC) return true;
        else {
            System.out.println("checkCRC: failed reCRC:" + toHexByte(reCRC) + " crc:" + toHexByte(crc));
            return false;
        }
    }

    private static String toHexByte(int value) {
        value = value & 0xFF;
        String hex = Integer.toHexString(value).toUpperCase();
        return "0x" + (hex.length() == 1 ? "0" + hex : hex);
    }

    public static void main(String[] args) {
        //测试crc方法
        //A0 01 00 00 94
        //A0 01 00 00
        //A0 05 00 00 3F
        //A0 04 00 00 54
        byte[] data = {(byte) 0xA0, 0x04, 0x00, 0x00};
        byte result = getCRC(data);
        System.out.println("checkedCRC:" + toHexByte(result));
    }
}
