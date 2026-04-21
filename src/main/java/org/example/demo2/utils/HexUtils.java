package org.example.demo2.utils;

public class HexUtils {


//    /*
//    名称：CRC_Check(unsigned char* data,unsigned char size)
//    输入：data指向待校验数据的指针；size待校验数据的长度
//    输出：CRC校验值与定制UART_KEY相加的结果
//    简介：CRC-8 多项式为0x107取0x07
//    参数”UART_KEY”默认为0
//    */
//    unsigned char CRC_8_Check(volatile unsigned char *data, unsigned char size)
//    {
//        //检查data是否是空指针
//        if (data == NULL)
//        {
//            return 0;
//        }
//        unsigned char crc_data = 0x00; //初始化CRC校验值
//        while (size--)
//        {
//            crc_data ^= (*data++); //将待校验8bit数据与crc_data做异或，并将结果存入crc_data
//            for (unsigned char a = 0; a < 8; a++) //对此数据的8位依次进行处理
//            {
//                if (crc_data & 0x80) //高位为1，需要异或；否则，不需要
//                {
//                    crc_data = (crc_data << 1u) ^ 0x07;
//                }
//                else
//                {
//                    crc_data = crc_data << 1u;
//                }
//            }
//        }
//        return crc_data;
//    }

    /**
     * 根据指定的数据和指定长度生成crc
     * 参考上面 厂商文档中提供的cpp代码
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
            System.out.println("checkCRC: failed reCRC:" + byteToHexString(reCRC) + " crc:" + byteToHexString(crc));
            return false;
        }
    }

    /**
     * 将字节数组转化成字符
     */
    public static String bytesToHexString(byte[] src) {
        StringBuilder stringBuilder = new StringBuilder();
        if (src == null || src.length <= 0) return stringBuilder.toString();
        for (byte aSrc : src) {
            int v = aSrc & 0xFF;
            String hv = Integer.toHexString(v);
            if (hv.length() < 2) stringBuilder.append(0);
            stringBuilder.append(hv).append(" ");
        }
        return stringBuilder.toString().toUpperCase();
    }

    public static String byteToHexString(byte b) {
        StringBuilder stringBuilder = new StringBuilder();
        int v = b & 0xFF;
        String hv = Integer.toHexString(v);
        if (hv.length() < 2) stringBuilder.append(0);
        stringBuilder.append(hv);
        return stringBuilder.toString().toUpperCase();
    }


    public static void main(String[] args) {
        //测试crc方法
        //A0 01 00 00 94
        //A0 02 00 00 29
        //A0 03 00 00 42
        //A0 04 00 00 54
        //A0 05 00 00 3F

        //A0 05 11 00 7D
        //A0 04 11 00 16
        //A0 03 11 00 00
        //A0 02 11 00 6B
        //A0 01 11 00 D6
//        byte[] data = {(byte) 0xA0, 0x04, 0x00, 0x00};
        byte[] data = {(byte) 0xA0, (byte)0x01, (byte)0x00, (byte)0x00};
        byte result = getCRC(data);
        System.out.println("checkedCRC:" + byteToHexString(result));

        byte a = (byte) 0x82;

        System.out.println("test1:"+(byteToHexString((byte) (a & 0x7f))));
        System.out.println("test2:"+(byteToHexString((byte) (a & 0x80))));
        System.out.println("test2:"+(byteToHexString((byte) (0x00 & 0x80))));
    }
}
