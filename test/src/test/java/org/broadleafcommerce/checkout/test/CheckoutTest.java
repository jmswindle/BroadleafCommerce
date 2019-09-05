/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.broadleafcommerce.checkout.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.broadleafcommerce.catalog.dao.SkuDao;
import org.broadleafcommerce.catalog.domain.Sku;
import org.broadleafcommerce.catalog.domain.SkuImpl;
import org.broadleafcommerce.checkout.service.CheckoutService;
import org.broadleafcommerce.checkout.service.workflow.CheckoutResponse;
import org.broadleafcommerce.order.domain.DiscreteOrderItem;
import org.broadleafcommerce.order.domain.DiscreteOrderItemImpl;
import org.broadleafcommerce.order.domain.FulfillmentGroup;
import org.broadleafcommerce.order.domain.FulfillmentGroupImpl;
import org.broadleafcommerce.order.domain.Order;
import org.broadleafcommerce.order.domain.OrderImpl;
import org.broadleafcommerce.order.domain.OrderItem;
import org.broadleafcommerce.payment.domain.CreditCardPaymentInfo;
import org.broadleafcommerce.payment.domain.PaymentInfo;
import org.broadleafcommerce.payment.domain.PaymentInfoImpl;
import org.broadleafcommerce.payment.domain.Referenced;
import org.broadleafcommerce.payment.service.SecurePaymentInfoService;
import org.broadleafcommerce.payment.service.type.PaymentInfoType;
import org.broadleafcommerce.profile.dao.CustomerDao;
import org.broadleafcommerce.profile.domain.Address;
import org.broadleafcommerce.profile.domain.AddressImpl;
import org.broadleafcommerce.profile.domain.Customer;
import org.broadleafcommerce.profile.domain.CustomerImpl;
import org.broadleafcommerce.profile.domain.State;
import org.broadleafcommerce.profile.domain.StateImpl;
import org.broadleafcommerce.test.dataprovider.SkuDaoDataProvider;
import org.broadleafcommerce.test.integration.BaseTest;
import org.broadleafcommerce.util.money.Money;
import org.testng.annotations.Test;

public class CheckoutTest extends BaseTest {

    @Resource(name="blCheckoutService")
    private CheckoutService checkoutService;

    @Resource(name="blSecurePaymentInfoService")
    private SecurePaymentInfoService securePaymentInfoService;
    
    @Resource
    private SkuDao skuDao;
    
    @Resource
    private CustomerDao customerDao;

    @Test(dependsOnGroups = { "createCustomers", "createSku", "testShippingInsert" })
    public void testCheckout() throws Exception {
        Order order = new OrderImpl();
        order.setCustomer(customerDao.readCustomerByUsername("customer1"));
        FulfillmentGroup group = new FulfillmentGroupImpl();
        List<FulfillmentGroup> groups = new ArrayList<FulfillmentGroup>();
        group.setOrder(order);
        group.setMethod("standard");
        groups.add(group);
        order.setFulfillmentGroups(groups);
        Money total = new Money(8.5D);
        group.setShippingPrice(total);

        DiscreteOrderItem item = new DiscreteOrderItemImpl();
        item.setPrice(new Money(10D));
        item.setQuantity(1);
        item.setSku(skuDao.readFirstSku());
        List<OrderItem> items = new ArrayList<OrderItem>();
        items.add(item);
        order.setOrderItems(items);

        order.setTotalShipping(new Money(8.5D));

        PaymentInfo payment = new PaymentInfoImpl();
        Address address = new AddressImpl();
        address.setAddressLine1("123 Test Rd");
        address.setCity("Dallas");
        address.setFirstName("Jeff");
        address.setLastName("Fischer");
        address.setPostalCode("75240");
        address.setPrimaryPhone("972-978-9067");
        State state = new StateImpl();
        state.setAbbreviation("TX");
        address.setState(state);
        payment.setAddress(address);
        payment.setAmount(new Money(18.5D + (18.5D * 0.05D)));
        payment.setReferenceNumber("1234");
        payment.setType(PaymentInfoType.CREDIT_CARD);
        payment.setOrder(order);

        CreditCardPaymentInfo cc = (CreditCardPaymentInfo) securePaymentInfoService.create(PaymentInfoType.CREDIT_CARD);
        cc.setExpirationMonth(11);
        cc.setExpirationYear(2030);
        cc.setPan("1111111111111111");
        cc.setCvvCode("123");
        cc.setReferenceNumber("1234");

        Map<PaymentInfo, Referenced> map = new HashMap<PaymentInfo, Referenced>();
        map.put(payment, cc);

        CheckoutResponse response = checkoutService.performCheckout(order, map);

        assert (order.getTotal().greaterThan(order.getSubTotal()));
        assert (order.getTotalTax().equals(order.getSubTotal().multiply(0.05D).add(order.getTotalShipping().multiply(0.05D))));
        assert (order.getTotal().equals(order.getSubTotal().add(order.getTotalTax()).add(group.getShippingPrice())));
        assert (response.getPaymentResponse().getResponseItems().size() > 0);
    }
}
