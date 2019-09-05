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
package org.broadleafcommerce.order.service;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Resource;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.broadleafcommerce.catalog.dao.CategoryDao;
import org.broadleafcommerce.catalog.dao.ProductDao;
import org.broadleafcommerce.catalog.dao.SkuDao;
import org.broadleafcommerce.catalog.domain.Category;
import org.broadleafcommerce.catalog.domain.Product;
import org.broadleafcommerce.catalog.domain.Sku;
import org.broadleafcommerce.offer.dao.OfferDao;
import org.broadleafcommerce.offer.domain.Offer;
import org.broadleafcommerce.order.dao.FulfillmentGroupDao;
import org.broadleafcommerce.order.dao.FulfillmentGroupItemDao;
import org.broadleafcommerce.order.dao.OrderDao;
import org.broadleafcommerce.order.domain.BundleOrderItem;
import org.broadleafcommerce.order.domain.DiscreteOrderItem;
import org.broadleafcommerce.order.domain.FulfillmentGroup;
import org.broadleafcommerce.order.domain.FulfillmentGroupItem;
import org.broadleafcommerce.order.domain.GiftWrapOrderItem;
import org.broadleafcommerce.order.domain.Order;
import org.broadleafcommerce.order.domain.OrderItem;
import org.broadleafcommerce.order.domain.PersonalMessage;
import org.broadleafcommerce.order.service.call.BundleOrderItemRequest;
import org.broadleafcommerce.order.service.call.DiscreteOrderItemRequest;
import org.broadleafcommerce.order.service.call.FulfillmentGroupItemRequest;
import org.broadleafcommerce.order.service.call.FulfillmentGroupRequest;
import org.broadleafcommerce.order.service.call.GiftWrapOrderItemRequest;
import org.broadleafcommerce.order.service.call.OrderItemRequest;
import org.broadleafcommerce.order.service.exception.ItemNotFoundException;
import org.broadleafcommerce.order.service.type.OrderStatus;
import org.broadleafcommerce.payment.dao.PaymentInfoDao;
import org.broadleafcommerce.payment.domain.PaymentInfo;
import org.broadleafcommerce.payment.domain.Referenced;
import org.broadleafcommerce.payment.service.SecurePaymentInfoService;
import org.broadleafcommerce.payment.service.type.PaymentInfoType;
import org.broadleafcommerce.pricing.service.advice.PricingExecutionManager;
import org.broadleafcommerce.pricing.service.exception.PricingException;
import org.broadleafcommerce.profile.domain.Address;
import org.broadleafcommerce.profile.domain.Customer;
import org.broadleafcommerce.workflow.WorkflowException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("blOrderService")
public class OrderServiceImpl implements org.broadleafcommerce.order.service.OrderService {

    private static final Log LOG = LogFactory.getLog(OrderServiceImpl.class);

    @Resource(name = "blOrderDao")
    protected OrderDao orderDao;

    @Resource(name = "blPaymentInfoDao")
    protected PaymentInfoDao paymentInfoDao;

    @Resource(name = "blFulfillmentGroupDao")
    protected FulfillmentGroupDao fulfillmentGroupDao;

    @Resource(name = "blFulfillmentGroupItemDao")
    protected FulfillmentGroupItemDao fulfillmentGroupItemDao;

    @Resource(name = "blOfferDao")
    protected OfferDao offerDao;

    @Resource(name = "blPricingExecutionManager")
    protected PricingExecutionManager pricingExecutionManager;

    @Resource(name = "blOrderItemService")
    protected org.broadleafcommerce.order.service.OrderItemService orderItemService;

    @Resource(name = "blSkuDao")
    protected SkuDao skuDao;

    @Resource(name = "blProductDao")
    protected ProductDao productDao;

    @Resource(name = "blCategoryDao")
    protected CategoryDao categoryDao;

    @Resource(name = "blFulfillmentGroupService")
    protected org.broadleafcommerce.order.service.FulfillmentGroupService fulfillmentGroupService;

    @Resource(name = "blSecurePaymentInfoService")
    protected SecurePaymentInfoService securePaymentInfoService;

    protected boolean rollupOrderItems = true;

    public Order createNamedOrderForCustomer(String name, Customer customer) {
        Order namedOrder = orderDao.create();
        namedOrder.setCustomer(customer);
        namedOrder.setName(name);
        namedOrder.setStatus(OrderStatus.NAMED);
        return persistOrder(namedOrder);
    }

    public Order save(Order order, boolean priceOrder) throws PricingException {
        return updateOrder(order, priceOrder);
    }

    public Order findOrderById(Long orderId) {
        return orderDao.readOrderById(orderId);
    }

    public List<Order> findOrdersForCustomer(Customer customer) {
        return orderDao.readOrdersForCustomer(customer.getId());
    }

    public List<Order> findOrdersForCustomer(Customer customer, OrderStatus status) {
        return orderDao.readOrdersForCustomer(customer, status);
    }

    public Order findNamedOrderForCustomer(String name, Customer customer) {
        return orderDao.readNamedOrderForCustomer(customer, name);
    }

    public FulfillmentGroup findDefaultFulfillmentGroupForOrder(Order order) {
        FulfillmentGroup fg = fulfillmentGroupDao.readDefaultFulfillmentGroupForOrder(order);

        return fg;
    }

    public OrderItem addSkuToOrder(Long orderId, Long skuId, Long productId, Long categoryId, Integer quantity, boolean priceOrder) throws PricingException {
        if (orderId == null || skuId == null || quantity == null) {
            return null;
        }

        Order order = findOrderById(orderId);
        Sku sku = skuDao.readSkuById(skuId);

        Product product;
        if (productId != null) {
            product = productDao.readProductById(productId);
        } else {
            product = null;
        }
        Category category;
        if (categoryId != null) {
            category = categoryDao.readCategoryById(categoryId);
        } else {
            category = null;
        }

        DiscreteOrderItemRequest itemRequest = new DiscreteOrderItemRequest();
        itemRequest.setCategory(category);
        itemRequest.setProduct(product);
        itemRequest.setQuantity(quantity);
        itemRequest.setSku(sku);

        return addDiscreteItemToOrder(order, itemRequest, priceOrder);
    }

    public OrderItem addDiscreteItemToOrder(Order order, DiscreteOrderItemRequest itemRequest, boolean priceOrder) throws PricingException {
        DiscreteOrderItem item = orderItemService.createDiscreteOrderItem(itemRequest);
        return addOrderItemToOrder(order, item, priceOrder);
    }

    public OrderItem addGiftWrapItemToOrder(Order order, GiftWrapOrderItemRequest itemRequest, boolean priceOrder) throws PricingException {
        GiftWrapOrderItem item = orderItemService.createGiftWrapOrderItem(itemRequest);
        return addOrderItemToOrder(order, item, priceOrder);
    }

    public OrderItem addBundleItemToOrder(Order order, BundleOrderItemRequest itemRequest, boolean priceOrder) throws PricingException {
        BundleOrderItem item = orderItemService.createBundleOrderItem(itemRequest);
        return addOrderItemToOrder(order, item, priceOrder);
    }

    public List<OrderItem> addItemsToOrder(Order order, List<OrderItemRequest> orderItemRequests, boolean priceOrder) throws PricingException {
        List<OrderItem> orderItems = new ArrayList<OrderItem>();
        for (OrderItemRequest orderItemRequest : orderItemRequests) {
            if (orderItemRequest instanceof DiscreteOrderItemRequest) {
                orderItems.add(orderItemService.createDiscreteOrderItem((DiscreteOrderItemRequest) orderItemRequest));
            } else if (orderItemRequest instanceof BundleOrderItemRequest) {
                orderItems.add(orderItemService.createBundleOrderItem((BundleOrderItemRequest) orderItemRequest));
            } else if (orderItemRequest instanceof GiftWrapOrderItemRequest) {
                orderItems.add(orderItemService.createGiftWrapOrderItem((GiftWrapOrderItemRequest) orderItemRequest));
            }
        }
        List<OrderItem> addedItems = addOrderItemsToOrder(order, orderItems, priceOrder);
        return addedItems;
    }

    public Order removeItemFromOrder(Long orderId, Long itemId, boolean priceOrder) throws PricingException {
        Order order = findOrderById(orderId);
        OrderItem orderItem = orderItemService.readOrderItemById(itemId);
        return removeItemFromOrder(order, orderItem, priceOrder);
    }

    public Order removeItemFromOrder(Order order, OrderItem item, boolean priceOrder) throws PricingException {
        removeOrderItemFromFullfillmentGroup(order, item);
        OrderItem itemFromOrder = order.getOrderItems().remove(order.getOrderItems().indexOf(item));
        orderItemService.delete(itemFromOrder);
        order = updateOrder(order, priceOrder);
        return order;
    }

    public Order removeItemsFromOrder(Order order, List<OrderItem> items, boolean priceOrder) throws PricingException {
        for (OrderItem item : items) {
            OrderItem itemFromOrder = order.getOrderItems().remove(order.getOrderItems().indexOf(item));
            orderItemService.delete(itemFromOrder);
            removeOrderItemFromFullfillmentGroup(order, item);
        }
        order = updateOrder(order, priceOrder);
        return order;
    }

    public Order removeItemFromOrder(Order order, List<OrderItem> items, boolean priceOrder) throws PricingException {
        for (OrderItem item : items) {
            OrderItem itemFromOrder = order.getOrderItems().remove(order.getOrderItems().indexOf(item));
            orderItemService.delete(itemFromOrder);
            removeOrderItemFromFullfillmentGroup(order, item);
        }
        order = updateOrder(order, priceOrder);
        return order;
    }

    public PaymentInfo addPaymentToOrder(Order order, PaymentInfo payment) {
        return addPaymentToOrder(order, payment, null);
    }

    public PaymentInfo addPaymentToOrder(Order order, PaymentInfo payment, Referenced securePaymentInfo) {
        payment.setOrder(order);
        order.getPaymentInfos().add(payment);
        order = persistOrder(order);
        int paymentIndex = order.getPaymentInfos().size() - 1;

        if (securePaymentInfo != null) {
            securePaymentInfoService.save(securePaymentInfo);
        }

        return order.getPaymentInfos().get(paymentIndex);
    }

    public void removeAllPaymentsFromOrder(Order order) {
        removePaymentsFromOrder(order, null);
    }

    public void removePaymentsFromOrder(Order order, PaymentInfoType paymentInfoType) {
        List<PaymentInfo> infos = new ArrayList<PaymentInfo>();
        for (PaymentInfo paymentInfo : order.getPaymentInfos()) {
            if (paymentInfoType == null || paymentInfoType.equals(paymentInfo.getType())) {
                infos.add(paymentInfo);
            }
        }
        order.getPaymentInfos().removeAll(infos);
        for (PaymentInfo paymentInfo : infos) {
            try {
                securePaymentInfoService.findAndRemoveSecurePaymentInfo(paymentInfo.getReferenceNumber(), paymentInfo.getType());
            } catch (WorkflowException e) {
                // do nothing--this is an acceptable condition
                LOG.debug("No secure payment is associated with the PaymentInfo", e);
            }
            order.getPaymentInfos().remove(paymentInfo);
            paymentInfo = paymentInfoDao.readPaymentInfoById(paymentInfo.getId());
            paymentInfoDao.delete(paymentInfo);
        }
    }

    public FulfillmentGroup addFulfillmentGroupToOrder(FulfillmentGroupRequest fulfillmentGroupRequest, boolean priceOrder) throws PricingException {
        FulfillmentGroup fg = fulfillmentGroupDao.create();
        fg.setAddress(fulfillmentGroupRequest.getAddress());
        fg.setOrder(fulfillmentGroupRequest.getOrder());
        fg.setPhone(fulfillmentGroupRequest.getPhone());
        fg.setMethod(fulfillmentGroupRequest.getMethod());
        for (FulfillmentGroupItemRequest request : fulfillmentGroupRequest.getFulfillmentGroupItemRequests()) {
            fg = addItemToFulfillmentGroup(request.getOrderItem(), fg, request.getQuantity(), false);
        }
        updateOrder(fg.getOrder(), priceOrder);

        return fg;
    }

    public FulfillmentGroup addFulfillmentGroupToOrder(Order order, FulfillmentGroup fulfillmentGroup, boolean priceOrder) throws PricingException {
        FulfillmentGroup dfg = findDefaultFulfillmentGroupForOrder(order);
        if (dfg == null) {
            fulfillmentGroup.setPrimary(true);
        } else if (dfg.equals(fulfillmentGroup)) {
            // API user is trying to re-add the default fulfillment group to the
            // same order
            fulfillmentGroup.setPrimary(true);
            order.getFulfillmentGroups().remove(dfg);
            // fulfillmentGroupDao.delete(dfg);
        }

        fulfillmentGroup.setOrder(order);
        // 1) For each item in the new fulfillment group
        for (FulfillmentGroupItem fgItem : fulfillmentGroup.getFulfillmentGroupItems()) {
            // 2) Find the item's existing fulfillment group
            for (FulfillmentGroup fg : order.getFulfillmentGroups()) {
                // If the existing fulfillment group is different than passed in
                // fulfillment group
                if (!fg.equals(fulfillmentGroup)) {
                    // 3) remove item from it's existing fulfillment
                    // group
                    fg.getFulfillmentGroupItems().remove(fgItem);
                }
            }
        }
        fulfillmentGroup = fulfillmentGroupDao.save(fulfillmentGroup);
        order.getFulfillmentGroups().add(fulfillmentGroup);
        int fulfillmentGroupIndex = order.getFulfillmentGroups().size() - 1;
        order = updateOrder(order, priceOrder);
        return order.getFulfillmentGroups().get(fulfillmentGroupIndex);
    }

    public FulfillmentGroup addItemToFulfillmentGroup(OrderItem item, FulfillmentGroup fulfillmentGroup, int quantity, boolean priceOrder) throws PricingException {
        Order order = item.getOrder();
        if (fulfillmentGroup.getId() == null) {
            // API user is trying to add an item to a fulfillment group not
            // created
            fulfillmentGroup = addFulfillmentGroupToOrder(order, fulfillmentGroup, priceOrder);
        }
        // API user is trying to add an item to an existing fulfillment group
        // Steps are
        // 1) Find the item's existing fulfillment group
        for (FulfillmentGroup fg : order.getFulfillmentGroups()) {
            Iterator<FulfillmentGroupItem> itr = fg.getFulfillmentGroupItems().iterator();
            while (itr.hasNext()) {
                FulfillmentGroupItem fgItem = itr.next();
                if (fgItem.getOrderItem().equals(item)) {
                    // 2) remove item from it's existing fulfillment group
                    itr.remove();
                    fulfillmentGroupItemDao.delete(fgItem);
                }
            }
        }
        FulfillmentGroupItem fgi = createFulfillmentGroupItemFromOrderItem(item, fulfillmentGroup, quantity);
        fgi = fulfillmentGroupItemDao.save(fgi);

        // 3) add the item to the new fulfillment group
        fulfillmentGroup.addFulfillmentGroupItem(fgi);
        order = updateOrder(order, priceOrder);
        fulfillmentGroup.setOrder(order);

        return fulfillmentGroup;
    }

    public FulfillmentGroup addItemsToFulfillmentGroup(List<OrderItem> items, FulfillmentGroup fulfillmentGroup, boolean priceOrder) throws PricingException {
        Order order = null;
        if (fulfillmentGroup.getId() == null && items != null && !items.isEmpty()) {
            // API user is trying to add an item to a fulfillment group not
            // created
            order = items.get(0).getOrder();
            fulfillmentGroup = addFulfillmentGroupToOrder(order, fulfillmentGroup, false);
        } else {
            order = fulfillmentGroup.getOrder();
        }

        for (OrderItem item : items) {
            // API user is trying to add an item to an existing fulfillment
            // group
            // Steps are
            // 1) Find the item's existing fulfillment group
            for (FulfillmentGroup fg : order.getFulfillmentGroups()) {
                Iterator<FulfillmentGroupItem> itr = fg.getFulfillmentGroupItems().iterator();
                while (itr.hasNext()) {
                    FulfillmentGroupItem fgItem = itr.next();
                    if (fgItem.getOrderItem().equals(item)) {
                        // 2) remove item from it's existing fulfillment group
                        itr.remove();
                        fulfillmentGroupItemDao.delete(fgItem);
                    }
                }
            }
            FulfillmentGroupItem fgi = createFulfillmentGroupItemFromOrderItem(item, fulfillmentGroup, item.getQuantity());
            fgi = fulfillmentGroupItemDao.save(fgi);

            // 3) add the item to the new fulfillment group
            fulfillmentGroup.addFulfillmentGroupItem(fgi);
        }
        order = updateOrder(order, priceOrder);
        return fulfillmentGroup;
    }

    public FulfillmentGroup addItemToFulfillmentGroup(OrderItem item, FulfillmentGroup fulfillmentGroup, boolean priceOrder) throws PricingException {
        return addItemToFulfillmentGroup(item, fulfillmentGroup, item.getQuantity(), true);
    }

    public Order addOfferToOrder(Order order, String offerCode) {
        throw new UnsupportedOperationException();
    }

    public void updateItemQuantity(Order order, OrderItem item, boolean priceOrder) throws ItemNotFoundException, PricingException {
        // This isn't quite right. It will need to be changed later to reflect
        // the exact requirements we want.
        // item.setQuantity(quantity);
        // item.setOrder(order);
        if (!order.getOrderItems().contains(item)) {
            throw new ItemNotFoundException("Order Item (" + item.getId() + ") not found in Order (" + order.getId() + ")");
        }
        OrderItem itemFromOrder = order.getOrderItems().get(order.getOrderItems().indexOf(item));
        itemFromOrder.setQuantity(item.getQuantity());

        order = updateOrder(order, priceOrder);
    }

    public void removeAllFulfillmentGroupsFromOrder(Order order, boolean priceOrder) throws PricingException {
        if (order.getFulfillmentGroups() != null) {
            for (Iterator<FulfillmentGroup> iterator = order.getFulfillmentGroups().iterator(); iterator.hasNext();) {
                FulfillmentGroup fulfillmentGroup = iterator.next();
                iterator.remove();
                fulfillmentGroupDao.delete(fulfillmentGroup);
            }
            updateOrder(order, priceOrder);
        }
    }

    public void removeFulfillmentGroupFromOrder(Order order, FulfillmentGroup fulfillmentGroup, boolean priceOrder) throws PricingException {
        order.getFulfillmentGroups().remove(fulfillmentGroup);
        fulfillmentGroupDao.delete(fulfillmentGroup);
        updateOrder(order, priceOrder);
    }

    public Order removeOfferFromOrder(Order order, Offer offer, boolean priceOrder) throws PricingException {
        order.getCandidateOrderOffers().remove(offer);
        offerDao.delete(offer);
        order = updateOrder(order, priceOrder);
        return order;
    }

    public Order removeAllOffersFromOrder(Order order, boolean priceOrder) throws PricingException {
        order.getCandidateOrderOffers().clear();
        order = updateOrder(order, priceOrder);
        return order;
    }

    public void removeNamedOrderForCustomer(String name, Customer customer) {
        Order namedOrder = findNamedOrderForCustomer(name, customer);
        cancelOrder(namedOrder);
    }

    public Order confirmOrder(Order order) {
        // TODO Other actions needed to complete order
        // (such as calling something to make sure the order is fulfilled
        // somehow).
        // Code below is only a start.
        return orderDao.submitOrder(order);
    }

    public void cancelOrder(Order order) {
        orderDao.delete(order);
    }

    public List<PaymentInfo> readPaymentInfosForOrder(Order order) {
        return paymentInfoDao.readPaymentInfosForOrder(order);
    }

    public OrderItem addOrderItemToOrder(Order order, OrderItem newOrderItem, boolean priceOrder) throws PricingException {
        int orderItemIndex;
        List<OrderItem> orderItems = order.getOrderItems();
        boolean containsItem = orderItems.contains(newOrderItem);
        if (rollupOrderItems && containsItem) {
            OrderItem itemFromOrder = orderItems.get(orderItems.indexOf(newOrderItem));
            itemFromOrder.setQuantity(itemFromOrder.getQuantity() + newOrderItem.getQuantity());
            orderItemIndex = orderItems.indexOf(itemFromOrder);
        } else {
            orderItems.add(newOrderItem);
            newOrderItem.setOrder(order);
            orderItemIndex = orderItems.size() - 1;
        }

        // don't worry about fulfillment groups, since the phase for adding
        // items occurs before shipping arrangements

        order = updateOrder(order, priceOrder);

        return order.getOrderItems().get(orderItemIndex);
    }

    public List<OrderItem> addOrderItemsToOrder(Order order, List<OrderItem> newOrderItems, boolean priceOrder) throws PricingException {
        List<Integer> orderItemIndexes = new ArrayList<Integer>();
        List<OrderItem> orderItems = order.getOrderItems();
        for (OrderItem newOrderItem : newOrderItems) {
            boolean containsItem = orderItems.contains(newOrderItem);
            if (rollupOrderItems && containsItem) {
                OrderItem itemFromOrder = orderItems.get(orderItems.indexOf(newOrderItem));
                itemFromOrder.setQuantity(itemFromOrder.getQuantity() + newOrderItem.getQuantity());
                orderItemIndexes.add(orderItems.indexOf(itemFromOrder));
            } else {
                orderItems.add(newOrderItem);
                newOrderItem.setOrder(order);
                orderItemIndexes.add(orderItems.size() - 1);
            }
        }

        // don't worry about fulfillment groups, since the phase for adding
        // items occurs before shipping arrangements
        order = updateOrder(order, priceOrder);
        List<OrderItem> returnedOrderItems = new ArrayList<OrderItem>();
        for (Integer orderItemIndex : orderItemIndexes) {
            returnedOrderItems.add(order.getOrderItems().get(orderItemIndex));
        }
        return returnedOrderItems;
    }

    public FulfillmentGroup createDefaultFulfillmentGroup(Order order, Address address) {
        for (FulfillmentGroup fulfillmentGroup : order.getFulfillmentGroups()) {
            if (fulfillmentGroup.isPrimary()) {
                return fulfillmentGroup;
            }
        }

        FulfillmentGroup newFg = fulfillmentGroupService.createEmptyFulfillmentGroup();
        newFg.setOrder(order);
        newFg.setPrimary(true);
        newFg.setAddress(address);
        for (OrderItem orderItem : order.getOrderItems()) {
            newFg.addFulfillmentGroupItem(createFulfillmentGroupItemFromOrderItem(orderItem, newFg, orderItem.getQuantity()));
        }
        return newFg;
    }

    public boolean isRollupOrderItems() {
        return rollupOrderItems;
    }

    public void setRollupOrderItems(boolean rollupOrderItems) {
        this.rollupOrderItems = rollupOrderItems;
    }

    public OrderDao getOrderDao() {
        return orderDao;
    }

    public void setOrderDao(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    public PaymentInfoDao getPaymentInfoDao() {
        return paymentInfoDao;
    }

    public void setPaymentInfoDao(PaymentInfoDao paymentInfoDao) {
        this.paymentInfoDao = paymentInfoDao;
    }

    public FulfillmentGroupDao getFulfillmentGroupDao() {
        return fulfillmentGroupDao;
    }

    public void setFulfillmentGroupDao(FulfillmentGroupDao fulfillmentGroupDao) {
        this.fulfillmentGroupDao = fulfillmentGroupDao;
    }

    public FulfillmentGroupItemDao getFulfillmentGroupItemDao() {
        return fulfillmentGroupItemDao;
    }

    public void setFulfillmentGroupItemDao(FulfillmentGroupItemDao fulfillmentGroupItemDao) {
        this.fulfillmentGroupItemDao = fulfillmentGroupItemDao;
    }

    public PricingExecutionManager getPricingExecutionManager() {
        return pricingExecutionManager;
    }

    public void setPricingExecutionManager(PricingExecutionManager pricingExecutionManager) {
        this.pricingExecutionManager = pricingExecutionManager;
    }

    public org.broadleafcommerce.order.service.OrderItemService getOrderItemService() {
        return orderItemService;
    }

    public void setOrderItemService(org.broadleafcommerce.order.service.OrderItemService orderItemService) {
        this.orderItemService = orderItemService;
    }

    public Order findOrderByOrderNumber(String orderNumber) {
        return orderDao.readOrderByOrderNumber(orderNumber);
    }

    protected Order updateOrder(Order order, Boolean priceOrder) throws PricingException {
        if (priceOrder) {
            pricingExecutionManager.executePricing(order);
        } else {
            order = persistOrder(order);
        }
        return order;
    }

    protected Order persistOrder(Order order) {
        return orderDao.save(order);
    }

    protected FulfillmentGroupItem createFulfillmentGroupItemFromOrderItem(OrderItem orderItem, FulfillmentGroup fulfillmentGroup, int quantity) {
        FulfillmentGroupItem fgi = fulfillmentGroupItemDao.create();
        fgi.setFulfillmentGroup(fulfillmentGroup);
        fgi.setOrderItem(orderItem);
        fgi.setQuantity(quantity);
        fgi.setPrice(orderItem.getPrice());
        fgi.setRetailPrice(orderItem.getRetailPrice());
        fgi.setSalePrice(orderItem.getSalePrice());
        return fgi;
    }

    protected void removeOrderItemFromFullfillmentGroup(Order order, OrderItem orderItem) {
        List<FulfillmentGroup> fulfillmentGroups = order.getFulfillmentGroups();
        for (FulfillmentGroup fulfillmentGroup : fulfillmentGroups) {
            Iterator<FulfillmentGroupItem> itr = fulfillmentGroup.getFulfillmentGroupItems().iterator();
            while (itr.hasNext()) {
                FulfillmentGroupItem fulfillmentGroupItem = itr.next();
                if (fulfillmentGroupItem.getOrderItem().equals(orderItem)) {
                    itr.remove();
                    fulfillmentGroupItemDao.delete(fulfillmentGroupItem);
                }
            }
        }
    }

    protected DiscreteOrderItemRequest createDiscreteOrderItemRequest(DiscreteOrderItem discreteOrderItem) {
        DiscreteOrderItemRequest itemRequest = new DiscreteOrderItemRequest();
        itemRequest.setCategory(discreteOrderItem.getCategory());
        itemRequest.setProduct(discreteOrderItem.getProduct());
        itemRequest.setQuantity(discreteOrderItem.getQuantity());
        itemRequest.setSku(discreteOrderItem.getSku());

        if (discreteOrderItem.getPersonalMessage() != null) {
            PersonalMessage personalMessage = orderItemService.createPersonalMessage();
            try {
                BeanUtils.copyProperties(personalMessage, discreteOrderItem.getPersonalMessage());
                personalMessage.setId(null);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            itemRequest.setPersonalMessage(personalMessage);
        }

        return itemRequest;
    }

    protected BundleOrderItemRequest createBundleOrderItemRequest(BundleOrderItem bundleOrderItem, List<DiscreteOrderItemRequest> discreteOrderItemRequests) {
        BundleOrderItemRequest bundleOrderItemRequest = new BundleOrderItemRequest();
        bundleOrderItemRequest.setCategory(bundleOrderItem.getCategory());
        bundleOrderItemRequest.setName(bundleOrderItem.getName());
        bundleOrderItemRequest.setQuantity(bundleOrderItem.getQuantity());
        bundleOrderItemRequest.setDiscreteOrderItems(discreteOrderItemRequests);
        return bundleOrderItemRequest;
    }

    @Override
    public Order reloadOrder(Order order) {
        if (order == null || order.getId() == null) {
            return order;
        }

        return orderDao.readOrderById(order.getId(), true);
    }

    @Override
    @Transactional("blTransactionManager")
    public boolean acquireLock(Order order) {
        return orderDao.acquireLock(order);
    }
    @Override
    @Transactional("blTransactionManager")
    public boolean releaseLock(Order order) {
        return orderDao.releaseLock(order);
    }

}
