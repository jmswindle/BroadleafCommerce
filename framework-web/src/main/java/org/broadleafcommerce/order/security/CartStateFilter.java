/*
 * #%L
 * BroadleafCommerce Framework Web
 * %%
 * Copyright (C) 2009 - 2013 Broadleaf Commerce
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.broadleafcommerce.order.security;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.broadleafcommerce.order.domain.Order;
import org.broadleafcommerce.order.service.OrderLockManager;
import org.broadleafcommerce.order.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component("blCartStateFilter")
public class CartStateFilter extends OncePerRequestFilter implements Ordered {

    protected static final Log LOG = LogFactory.getLog(CartStateFilter.class);

    @Autowired
    protected Environment env;

    @Resource(name = "blCartStateRequestProcessor")
    protected CartStateRequestProcessor cartStateProcessor;

    @Resource(name = "blOrderLockManager")
    protected OrderLockManager orderLockManager;

    @Resource(name = "blOrderService")
    protected OrderService orderService;

    protected List<String> excludedOrderLockRequestPatterns;

    @Override
    public void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        cartStateProcessor.process(new ServletWebRequest(request, response));

        if (!requestRequiresLock(request)) {
            chain.doFilter(request, response);
            return;
        }

        Order order = (Order) request.getAttribute("cart");

        if (LOG.isTraceEnabled()) {
            LOG.trace("Thread[" + Thread.currentThread().getId() + "] attempting to lock order[" + order.getId() + "]");
        }

        Object lockObject = null;
        try {
            if (getErrorInsteadOfQueue()) {
                lockObject = orderLockManager.acquireLockIfAvailable(order);
                if (lockObject == null) {
                    // We weren't able to acquire the lock immediately because some other thread has it. Because the
                    // order.lock.errorInsteadOfQueue property was set to true, we're going to throw an exception now.
                    throw new OrderLockAcquisitionFailureException("Thread[" + Thread.currentThread().getId() +
                                                                   "] could not acquire lock for order[" + order.getId() + "]");
                }
            } else {
                lockObject = orderLockManager.acquireLock(order);
            }

            if (LOG.isTraceEnabled()) {
                LOG.trace("Thread[" + Thread.currentThread().getId() + "] grabbed lock for order[" + order.getId() + "]");
            }

            // When we have a hold of the lock for the order, we want to reload the order from the database.
            // This is because a different thread could have modified the order in between the time we initially
            // read it for this thread and now, resulting in the order being stale. Additionally, we want to make
            // sure we detach the order from the EntityManager and forcefully reload the order.
            request.setAttribute("cart", orderService.reloadOrder(order));

            chain.doFilter(request, response);
        } finally {
            if (lockObject != null) {
                orderLockManager.releaseLock(lockObject);
            }

            if (LOG.isTraceEnabled()) {
                LOG.trace("Thread[" + Thread.currentThread().getId() + "] released lock for order[" + order.getId() +"]");
            }
        }
    }

    /**
     * By default, all POST requests that are not matched by the {@link #getExcludedOrderLockRequestPatterns()} list
     * (using the {@link AntPathRequestMatcher}) will be marked as requiring a lock on the Order.
     *
     * @param req
     * @return whether or not the current request requires a lock on the order
     */
    protected boolean requestRequiresLock(ServletRequest req) {
        if (!(req instanceof HttpServletRequest)) {
            return false;
        }

        if (!orderLockManager.isActive()) {
            return false;
        }

        HttpServletRequest request = (HttpServletRequest) req;

        if (!request.getMethod().equalsIgnoreCase("post")) {
            return false;
        }

        if (excludedOrderLockRequestPatterns != null && excludedOrderLockRequestPatterns.size() > 0) {
            for (String pattern : excludedOrderLockRequestPatterns) {
                RequestMatcher matcher = new AntPathRequestMatcher(pattern);
                if (matcher.matches(request)){
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public int getOrder() {
        //FilterChainOrder has been dropped from Spring Security 3
        //return FilterChainOrder.REMEMBER_ME_FILTER+1;
        return 1502;
    }

    public List<String> getExcludedOrderLockRequestPatterns() {
        return excludedOrderLockRequestPatterns;
    }

    /**
     * This allows you to declaratively set a list of excluded Request Patterns
     *
     * <bean id="blCartStateFilter" class="org.broadleafcommerce.core.web.order.security.CartStateFilter">
     *     <property name="excludedOrderLockRequestPatterns">
     *         <list>
     *             <value>/exclude-me/**</value>
     *         </list>
     *     </property>
     * </bean>
     *
     **/
    public void setExcludedOrderLockRequestPatterns(List<String> excludedOrderLockRequestPatterns) {
        this.excludedOrderLockRequestPatterns = excludedOrderLockRequestPatterns;
    }

    protected boolean getErrorInsteadOfQueue() {
        return Boolean.valueOf(env.getProperty("order.lock.errorInsteadOfQueue"));
    }

}
