package lab.idioglossia.row.filter;

import lab.idioglossia.row.config.Naming;
import lab.idioglossia.row.domain.RowResponseStatus;
import lab.idioglossia.row.domain.RowWebsocketSession;
import lab.idioglossia.row.domain.protocol.RequestDto;
import lab.idioglossia.row.domain.protocol.ResponseDto;
import lab.idioglossia.row.event.Subscription;
import lab.idioglossia.row.exception.InvalidPathException;
import lab.idioglossia.row.service.SubscriberService;
import lab.idioglossia.row.utl.RequestResponseUtil;

import static lab.idioglossia.row.config.Naming.SUBSCRIPTION_EVENT_HEADER_NAME;
import static lab.idioglossia.row.config.Naming.SUBSCRIPTION_Id_HEADER_NAME;

public class SubscribeFilter implements RowFilter {
    private final SubscriberService subscriberService;
    private final boolean pre;

    public SubscribeFilter(SubscriberService subscriberService, boolean pre) {
        this.subscriberService = subscriberService;
        this.pre = pre;
    }

    @Override
    public boolean filter(RequestDto requestDto, ResponseDto responseDto, RowWebsocketSession rowWebsocketSession) throws Exception {
        try {
            if(RequestResponseUtil.isUnSubscribing(requestDto)){
                subscriberService.handleUnsubscribe(requestDto, pre);
            } else{
                Subscription subscription = subscriberService.handleSubscription(requestDto, pre);
                if(subscription != null){
                    RequestResponseUtil.addSubscriptionDetails(subscription, responseDto);
                }
            }
        } catch (InvalidPathException e){
            responseDto.setStatus(RowResponseStatus.NOT_FOUND);
        }
        return true;
    }
}
