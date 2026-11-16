import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.content.AttributionSource;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Roda via app_process como usuário shell (que tem BLUETOOTH_PRIVILEGED)
 * e muda a Classe de Dispositivo do Bluetooth do celular.
 * Uso: app_process / SetCod [get | <cod em hex, ex. 002508>]
 */
public class SetCod {
    public static void main(String[] args) throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        AttributionSource src = new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        Method create = BluetoothAdapter.class.getDeclaredMethod("createAdapter", AttributionSource.class);
        create.setAccessible(true);
        BluetoothAdapter a = (BluetoothAdapter) create.invoke(null, src);

        Method get = BluetoothAdapter.class.getDeclaredMethod("getBluetoothClass");
        get.setAccessible(true);
        System.out.println("antes: " + describe(get.invoke(a)));
        if (args.length > 0 && !args[0].equals("get")) {
            int cod = Integer.parseInt(args[0], 16);
            Constructor<BluetoothClass> c = BluetoothClass.class.getDeclaredConstructor(int.class);
            c.setAccessible(true);
            Method set = BluetoothAdapter.class.getDeclaredMethod("setBluetoothClass", BluetoothClass.class);
            set.setAccessible(true);
            System.out.println("setBluetoothClass -> " + set.invoke(a, c.newInstance(cod)));
            Thread.sleep(500);
            System.out.println("depois: " + describe(get.invoke(a)));
        }
    }

    static String describe(Object bc) throws Exception {
        if (bc == null) return "null";
        Method m = BluetoothClass.class.getDeclaredMethod("getClassOfDevice");
        m.setAccessible(true);
        return String.format("0x%06X", (Integer) m.invoke(bc));
    }
}
