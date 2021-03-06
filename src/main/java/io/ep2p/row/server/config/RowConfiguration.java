package io.ep2p.row.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ep2p.row.server.config.properties.HandlerProperties;
import io.ep2p.row.server.config.properties.RowProperties;
import io.ep2p.row.server.config.properties.WebSocketProperties;
import io.ep2p.row.server.filter.RowFilter;
import io.ep2p.row.server.filter.RowFilterChain;
import io.ep2p.row.server.filter.RowInvokerFiler;
import io.ep2p.row.server.filter.SubscribeFilter;
import io.ep2p.row.server.repository.EndpointRepository;
import io.ep2p.row.server.repository.RowSessionRegistry;
import io.ep2p.row.server.repository.SetEndpointRepository;
import io.ep2p.row.server.repository.SubscriptionRegistry;
import io.ep2p.row.server.service.*;
import io.ep2p.row.server.ws.*;
import io.ep2p.row.server.service.*;
import io.ep2p.row.server.ws.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties({WebSocketProperties.class, HandlerProperties.class, RowProperties.class})
@ConditionalOnProperty(value = "row.enable", havingValue = "true")
@Import({WebsocketConfiguration.class, RowApplicationListener.class})
public class RowConfiguration {
    private final WebSocketProperties webSocketProperties;
    private final HandlerProperties handlerProperties;

    @Autowired
    public RowConfiguration(WebSocketProperties webSocketProperties, HandlerProperties handlerProperties) {
        this.webSocketProperties = webSocketProperties;
        this.handlerProperties = handlerProperties;
    }

    @Bean
    @ConditionalOnMissingBean(EndpointRepository.class)
    public EndpointRepository endpointRepository(){
        return new SetEndpointRepository();
    }

    @Bean
    @ConditionalOnMissingBean({RowScanner.class})
    @DependsOn({"endpointRepository"})
    public RowScanner rowScanner(EndpointRepository endpointRepository){
        return new RowScanner(endpointRepository);
    }

    @Bean
    @ConditionalOnMissingBean({EndpointProvider.class})
    @DependsOn({"endpointRepository"})
    public EndpointProvider endpointProvider(EndpointRepository endpointRepository){
        return new DefaultEndpointProvider(endpointRepository);
    }

    @Bean("rowSessionRegistry")
    @ConditionalOnMissingBean({RowSessionRegistry.class})
    public RowSessionRegistry rowSessionRegistry(){
        if(handlerProperties.isAllowMultipleSessionsForSingleUser()){
            return new RowSessionRegistry.MultiUserSessionRegistry();
        }
        return new RowSessionRegistry.DefaultRowSessionRegistry();
    }

    @Bean("rowInvokerService")
    @DependsOn({"endpointProvider"})
    public RowInvokerService rowInvokerService(EndpointProvider endpointProvider){
        return new RowInvokerService(endpointProvider);
    }

    @Bean
    @ConditionalOnMissingBean({RowHandshakeAuthHandler.class})
    public RowHandshakeAuthHandler rowHandshakeAuthHandler(){
        return new RowHandshakeAuthHandler.DefaultRowHandshakeAuthHandler();
    }

    @Bean
    @ConditionalOnMissingBean({TokenExtractor.class})
    public TokenExtractor tokenExtractor(){
        return new TokenExtractor.NoTokenExtractor();
    }

    @Bean("rowHandshakeTokenInterceptor")
    @DependsOn({"rowHandshakeAuthHandler", "tokenExtractor"})
    public HandshakeInterceptor rowHandshakeInterceptor(RowHandshakeAuthHandler rowHandshakeAuthHandler, TokenExtractor tokenExtractor){
        return new RowHandshakeTokenInterceptor(rowHandshakeAuthHandler, tokenExtractor);
    }

    @Bean("rowInvokerFilter")
    @DependsOn("rowInvokerService")
    public RowFilter rowInvokerFilter(RowInvokerService rowInvokerService){
        return new RowInvokerFiler(rowInvokerService);
    }

    @Bean("rowFilterChain")
    @DependsOn({"rowInvokerFilter", "subscriberService"})
    public RowFilterChain rowFilterChain(RowFilter rowInvokerFilter, SubscriberService subscriberService){
        List<RowFilter> rowFilters = new CopyOnWriteArrayList<>();
        rowFilters.add(new SubscribeFilter(subscriberService, true));
        rowFilters.add(rowInvokerFilter);
        rowFilters.add(new SubscribeFilter(subscriberService, false));
        return new RowFilterChain(rowFilters);
    }

    @Bean("filterScanner")
    @DependsOn("rowFilterChain")
    public FilterScanner filterScanner(RowFilterChain rowFilterChain){
        return new FilterScanner(rowFilterChain);
    }

    @Bean("rowWsListener")
    @ConditionalOnMissingBean(RowWsListener.class)
    public RowWsListener rowWsListener(){
        return new RowWsListener.DummyRowWsListener();
    }

    @Bean("protocolService")
    @DependsOn("rowFilterChain")
    public ProtocolService protocolService(RowFilterChain rowFilterChain){
        return new ProtocolService(rowFilterChain);
    }

    @Bean("rowWebSocketHandler")
    @DependsOn({"rowSessionRegistry", "protocolService", "rowWsListener", "subscriptionRegistry"})
    @ConditionalOnMissingBean(value = WebSocketHandler.class, name = "rowWebSocketHandler")
    public WebSocketHandler rowWebSocketHandler(RowSessionRegistry rowSessionRegistry, ProtocolService protocolService, RowWsListener rowWsListener, SubscriptionRegistry subscriptionRegistry){
        return new RowWebSocketHandler(rowSessionRegistry, webSocketProperties, rowWsListener, subscriptionRegistry, protocolService, handlerProperties.isTrackHeartbeats());
    }

    @Bean("subscriptionRegistry")
    public SubscriptionRegistry subscriptionRegistry(){
        return new SubscriptionRegistry.RowSubscriptionRegistry();
    }

    @Bean("subscriberService")
    @DependsOn({"subscriptionRegistry", "endpointProvider"})
    public SubscriberService subscriberService(SubscriptionRegistry subscriptionRegistry, EndpointProvider endpointProvider){
        return new SubscriberService(subscriptionRegistry, endpointProvider);
    }

    @Bean("publisherService")
    @DependsOn({"subscriptionRegistry", "rowSessionRegistry", "objectMapper"})
    public PublisherService publisherService(SubscriptionRegistry subscriptionRegistry, RowSessionRegistry rowSessionRegistry, ObjectMapper objectMapper){
        return new PublisherService(subscriptionRegistry, rowSessionRegistry, objectMapper);
    }

    @Bean("rowTaskExecutor")
    public TaskExecutor rowTaskExecutor(RowProperties rowProperties){
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setQueueCapacity(10);
        taskExecutor.setQueueCapacity(rowProperties.getPublisherQueue());
        taskExecutor.setMaxPoolSize(rowProperties.getPublisherMaxPoolSize());
        taskExecutor.setAllowCoreThreadTimeOut(false);
        taskExecutor.setCorePoolSize(rowProperties.getPublisherCorePoolSize());
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setBeanName("rowTaskExecutor");
        return taskExecutor;
    }

    @Bean("objectMapper")
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper(){
        return new ObjectMapper();
    }

    @Bean("rawMessagePublisherService")
    @DependsOn("objectMapper")
    public RawMessagePublisherService rawMessagePublisherService(ObjectMapper objectMapper){
        return new RawMessagePublisherService(objectMapper);
    }

    //publisher proxy config
    /*@Bean
    @ConditionalOnMissingBean(EventPublisherProxy.class)
    @DependsOn(value = {"rowTaskExecutor","publisherService"})
    public EventPublisherProxy publisherProxy(TaskExecutor rowTaskExecutor, PublisherService publisherService) {
        return new EventPublisherProxy(rowTaskExecutor, publisherService);
    }

    @Bean(name = "publisherProxyBeanFactory")
    public PublisherProxyBeanFactory webServiceProxyBeanFactory() {
        return new PublisherProxyBeanFactory();
    }*/

}
