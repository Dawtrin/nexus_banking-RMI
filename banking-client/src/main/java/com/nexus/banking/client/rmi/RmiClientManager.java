package com.nexus.banking.client.rmi;

import com.nexus.banking.client.config.ClientPreferences;
import com.nexus.banking.contract.remote.BankingService;
import com.nexus.banking.contract.rmi.RmiSocketFactories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.rmi.ConnectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/** Looks up the banking service on a branch's RMI registry with bounded connect/read timeouts and optional TLS. */
public final class RmiClientManager {
    private static final Logger log = LoggerFactory.getLogger(RmiClientManager.class);
    public static final String BANKING_SERVICE_NAME = "NexusBankingService";

    private RmiClientManager() {
    }

    public static BankingService connect(String host, int port, boolean useSsl) throws RemoteException {
        log.info("Connecting to RMI registry at {}:{} (ssl={})", host, port, useSsl);
        try {
            Registry registry = LocateRegistry.getRegistry(host, port, RmiSocketFactories.clientFactory(useSsl));
            return (BankingService) registry.lookup(BANKING_SERVICE_NAME);
        } catch (ConnectException | java.rmi.ConnectIOException | java.rmi.UnknownHostException e) {
            throw new RemoteException("Không kết nối được tới " + host + ":" + port
                    + ". Kiểm tra máy chủ đã chạy chưa, địa chỉ, cổng, tường lửa và cấu hình TLS.", e);
        } catch (NotBoundException e) {
            throw new RemoteException("Dịch vụ '" + e.getMessage() + "' chưa được đăng ký trên " + host + ":" + port, e);
        }
    }

    public static BankingService connect(String host, int port) throws RemoteException {
        return connect(host, port, ClientPreferences.sslEnabled());
    }
}
