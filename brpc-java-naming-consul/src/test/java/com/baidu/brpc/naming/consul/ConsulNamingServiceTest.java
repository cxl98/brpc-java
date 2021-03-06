/*
 * Copyright (c) 2018 Baidu, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.baidu.brpc.naming.consul;

import com.baidu.brpc.client.channel.ServiceInstance;
import com.baidu.brpc.naming.BrpcURL;
import com.baidu.brpc.naming.NotifyListener;
import com.baidu.brpc.naming.RegisterInfo;
import com.baidu.brpc.protocol.SubscribeInfo;
import com.pszymczyk.consul.ConsulProcess;
import com.pszymczyk.consul.ConsulStarterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.junit.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Slf4j
@Ignore
public class ConsulNamingServiceTest {

    private static BrpcURL namingUrl;
    private static ConsulNamingService consulNamingService;
    private static ConsulProcess consul;

    @BeforeClass
    public static void setUp() throws Exception {
        final String customConfiguration =
                "{\n" +
                        "  \"datacenter\": \"dc-test\",\n" +
                        "  \"log_level\": \"info\"\n" +
                        "}\n";
        consul = ConsulStarterBuilder.consulStarter()
                .withConsulVersion("1.4.2")
                .withCustomConfig(customConfiguration)
                .build()
                .start();
        namingUrl = new BrpcURL("consul://127.0.0.1:" + consul.getHttpPort());
        consulNamingService = new ConsulNamingService(namingUrl);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        consulNamingService.destroy();
        consul.close();
        consulNamingService = null;
    }

    protected RegisterInfo createRegisterInfo(String host, int port) {
        RegisterInfo registerInfo = new RegisterInfo();
        registerInfo.setHost(host);
        registerInfo.setPort(port);
        registerInfo.setInterfaceName(EchoService.class.getName());
        return registerInfo;
    }

    protected SubscribeInfo createSubscribeInfo(boolean ignoreFail) {
        SubscribeInfo subscribeInfo = new SubscribeInfo();
        subscribeInfo.setInterfaceName(EchoService.class.getName());
        subscribeInfo.setIgnoreFailOfNamingService(ignoreFail);
        return subscribeInfo;
    }

    @Test
    public void testLookup() throws InterruptedException {
        SubscribeInfo subscribeInfo = createSubscribeInfo(true);
        List<ServiceInstance> instances = consulNamingService.lookup(subscribeInfo);
        Assert.assertTrue(instances.size() == 0);

        RegisterInfo registerInfo = createRegisterInfo("127.0.0.1", 8012);
        consulNamingService.register(registerInfo);
        Thread.sleep(3 * 1000);

        instances = consulNamingService.lookup(subscribeInfo);
        Assert.assertTrue(instances.size() == 1);
        Assert.assertTrue(instances.get(0).getIp().equals("127.0.0.1"));
        Assert.assertTrue(instances.get(0).getPort() == 8012);
        consulNamingService.unregister(registerInfo);
    }

    @Test
    public void testRegisterAndSubscribe() throws InterruptedException {
        RegisterInfo registerInfo = createRegisterInfo("127.0.0.1", 8015);
        RegisterInfo anotherRegisterInfo = createRegisterInfo("127.0.0.1", 8016);
        consulNamingService.register(registerInfo);
        consulNamingService.register(anotherRegisterInfo);
        Thread.sleep(3 * 1000);

        SubscribeInfo subscribeInfo = createSubscribeInfo(false);
        final List<ServiceInstance> adds = consulNamingService.lookup(subscribeInfo);
        Assert.assertTrue(adds.size() == 2);
        adds.clear();

        final List<ServiceInstance> deletes = new ArrayList<ServiceInstance>();
        consulNamingService.subscribe(subscribeInfo, new NotifyListener() {
            @Override public void notify(Collection<ServiceInstance> addList,
                                         Collection<ServiceInstance> deleteList) {
                adds.addAll(addList);
                deletes.addAll(deleteList);
                log.info("notify: {}, {}", addList, deleteList);
            }
        });

        consulNamingService.unregister(registerInfo);
        Thread.sleep(3 * 1000);
        Assert.assertTrue(adds.size() == 0);
        Assert.assertTrue(deletes.size() == 1);
        Assert.assertTrue(deletes.get(0).equals(new ServiceInstance("127.0.0.1", 8015)));
        adds.clear();
        deletes.clear();

        consulNamingService.register(registerInfo);
        Thread.sleep(3 * 1000);
        Assert.assertTrue(adds.size() == 1);
        Assert.assertTrue(deletes.size() == 0);
        Assert.assertTrue(adds.get(0).equals(new ServiceInstance("127.0.0.1", 8015)));
    }

}
