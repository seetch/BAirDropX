package org.by1337.bairx.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ItemSerializer {

    public static String serialize(ItemStack item) {
        try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(byteOut);
             BukkitObjectOutputStream dataOut = new BukkitObjectOutputStream(gzipOut)) {
            dataOut.writeObject(item);
            dataOut.flush();
            gzipOut.finish();
            return Base64.getEncoder().encodeToString(byteOut.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Ошибка сериализации предмета", e);
        }
    }

    public static ItemStack deserialize(String data) {
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             GZIPInputStream gzipIn = new GZIPInputStream(byteIn);
             BukkitObjectInputStream dataIn = new BukkitObjectInputStream(gzipIn)) {
            return (ItemStack) dataIn.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Ошибка десериализации предмета", e);
        }
    }
}
