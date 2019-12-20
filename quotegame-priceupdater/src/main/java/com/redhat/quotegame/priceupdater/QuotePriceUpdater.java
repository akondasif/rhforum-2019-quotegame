package com.redhat.quotegame.priceupdater;

import java.util.concurrent.ConcurrentLinkedQueue;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.redhat.quotegame.model.Order;
import com.redhat.quotegame.model.Quote;

import org.infinispan.client.hotrod.RemoteCache;
import org.jboss.logging.Logger;

//import io.quarkus.infinispan.client.runtime.Remote;
import io.quarkus.infinispan.client.Remote;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.kogito.rules.KieRuntimeBuilder;

@Path("/api/order")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
/**
 * Component that takes care of processing orders and updating portfolio 
 * @author laurent
 */
public class QuotePriceUpdater {
 
    private final Logger logger = Logger.getLogger(getClass());
    
    private KieSession ksession;

    private ConcurrentLinkedQueue<FactHandle> lastOrdersHandles = new ConcurrentLinkedQueue<>();

    @Inject
    @Named("quotePriceKS")
    QuotePriceUpdater( KieRuntimeBuilder runtimeBuilder ) {
        ksession = runtimeBuilder.newKieSession();
    }

    @Inject
    @Remote("quotegame-quotes")
    RemoteCache<String, Quote> quotesCache;

    @POST
    public Response considerOrder(Order order) {
        logger.info("Get new order to process...");

        logger.info("Get corresponding Quote ...");
        Quote quote = quotesCache.get(order.getQuote());
        int quoteBefore = quote.hashCode();

        logger.info("Inserting Order fact ...");
        FactHandle orderFH = ksession.insert(order);
        lastOrdersHandles.add(orderFH);

        logger.info("Inserting Quote fact ...");
        FactHandle quoteFH = ksession.insert(quote);

        logger.info("Fire Rules ...");
        int rulesFired = ksession.fireAllRules();
        logger.info("Number of rules fired = " + rulesFired);

        int quoteAfter = quote.hashCode();

        if (quoteBefore == quoteAfter){
            logger.info("Quote not modified");
        } else {
            logger.info("Updating Quote cache");
            quotesCache.replace(order.getQuote(), quote);
        }

        ksession.delete(quoteFH);
        logger.info("Number of facts in WM = " + ksession.getFactCount());
        if (ksession.getFactCount() > 5) {
            ksession.delete(lastOrdersHandles.remove());
        }

        return Response.ok(order).build();
    }
}