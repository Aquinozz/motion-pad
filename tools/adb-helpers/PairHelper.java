import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;
import android.os.Looper;
import java.lang.reflect.Method;

/** Roda como shell: quando o Switch começa a parear, confirma na hora (antes do HyperOS cancelar). */
public class PairHelper {
    public static void main(String[] args) throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        try { Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null); } catch (Throwable t) { System.out.println("init: " + t); }
        if (args.length == 0) { System.out.println("uso: PairHelper <endereço Bluetooth do Switch> [segundos]"); return; }
        String addr = args[0];
        long until = System.currentTimeMillis() + (args.length > 1 ? Long.parseLong(args[1]) : 180) * 1000L;
        AttributionSource src = new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        Method create = BluetoothAdapter.class.getDeclaredMethod("createAdapter", AttributionSource.class);
        create.setAccessible(true);
        BluetoothAdapter a = (BluetoothAdapter) create.invoke(null, src);
        BluetoothDevice d = a.getRemoteDevice(addr);
        int last = -1;
        System.out.println("vigiando " + addr + " estado=" + d.getBondState());
        while (System.currentTimeMillis() < until) {
            int s = d.getBondState();
            if (s != last) { System.out.println(System.currentTimeMillis() % 100000 + " estado=" + s); last = s; }
            if (s == BluetoothDevice.BOND_BONDING) {
                boolean ok = false;
                try { ok = d.setPairingConfirmation(true); } catch (Throwable t) { System.out.println("erro: " + t); }
                if (ok) System.out.println("confirmado!");
            } else if (s == BluetoothDevice.BOND_BONDED) {
                System.out.println("PAREADO");
                return;
            }
            Thread.sleep(2);
        }
        System.out.println("tempo esgotado");
    }
}
