package org.bigmouth.ticket4j;

import java.io.File;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.lang3.StringUtils;
import org.bigmouth.framework.core.SpringContextHolder;
import org.bigmouth.ticket4j.cookie.CookieCache;
import org.bigmouth.ticket4j.entity.Person;
import org.bigmouth.ticket4j.entity.Response;
import org.bigmouth.ticket4j.entity.Seat;
import org.bigmouth.ticket4j.entity.Token;
import org.bigmouth.ticket4j.entity.request.CheckOrderInfoRequest;
import org.bigmouth.ticket4j.entity.request.ConfirmSingleForQueueRequest;
import org.bigmouth.ticket4j.entity.request.QueryTicketRequest;
import org.bigmouth.ticket4j.entity.request.SubmitOrderRequest;
import org.bigmouth.ticket4j.entity.response.CheckOrderInfoResponse;
import org.bigmouth.ticket4j.entity.response.CheckUserResponse;
import org.bigmouth.ticket4j.entity.response.ConfirmSingleForQueueResponse;
import org.bigmouth.ticket4j.entity.response.NoCompleteOrderResponse;
import org.bigmouth.ticket4j.entity.response.OrderWaitTimeResponse;
import org.bigmouth.ticket4j.entity.response.QueryPassengerResponse;
import org.bigmouth.ticket4j.entity.response.QueryTicketResponse;
import org.bigmouth.ticket4j.entity.train.Train;
import org.bigmouth.ticket4j.http.Ticket4jHttpResponse;
import org.bigmouth.ticket4j.report.Report;
import org.bigmouth.ticket4j.report.TicketReport;
import org.bigmouth.ticket4j.utils.AntiUtils;
import org.bigmouth.ticket4j.utils.CharsetUtils;
import org.bigmouth.ticket4j.utils.PersonUtils;
import org.bigmouth.ticket4j.utils.StationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

public class TicketProcess {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(TicketProcess.class);
    
    private final CookieCache cookieCache;
    private final AntiUtils antiUtils;
    private final TicketReport ticketReport;
    
    private String passengers;
    private String seatSource;
    private String trainDate;
    private String trainFrom;
    private String trainTo;
    private String includeSource;
    private String excludeSource;
    
    private int queryTicketSleepTime = 1000;
    
    private boolean recognition = true;

    public TicketProcess(CookieCache cookieCache, AntiUtils antiUtils, TicketReport ticketReport) {
        this.cookieCache = cookieCache;
        this.antiUtils = antiUtils;
        this.ticketReport = ticketReport;
    }
    
    public void start() {
        try {
            final List<Person> persons = Person.of(passengers);
            final List<Seat> seats = Seat.of(seatSource);
    
            final Initialize initialize = SpringContextHolder.getBean("initialize");
            final PassCode passCode = SpringContextHolder.getBean("passCode");
            final User user = SpringContextHolder.getBean("user");
            final Ticket ticket = SpringContextHolder.getBean("ticket");
            final Order order = SpringContextHolder.getBean("order");
            
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("将要预订的车票信息：{} 从 {} 到 {} 的 {}", new String[] {
                        trainDate, trainFrom, trainTo, Seat.getDescription(seats)
                });
                LOGGER.info("乘车人信息：{}", persons.toString());
            }
            
            // 初始化Cookie及登录
            final Ticket4jHttpResponse response = initTicket4jHttpResponse(initialize, user);
            
            while (!response.isSignIn()) {
                byte[] code = null;
                File loginPassCode = passCode.getLoginPassCode(response);
                if (recognition) {
                    code = antiUtils.recognition(4, loginPassCode.getPath());
                }
                else {
                    System.out.println("请输入验证码(" + loginPassCode.getPath() + ")并回车确认：");
                    Scanner scanner = new Scanner(System.in);
                    code = scanner.next().getBytes();
                }
                Response login = user.login(new String(code), response);
                response.setSignIn(login.isContinue());
                if (!login.isContinue()) {
                    login.printMessage();
                    if (StringUtils.equals(login.getMessage(), "00：00至07：00为系统维护时间")) {
                        return;
                    }
                }
            }
            
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("正在检查乘车人是否允许购票...");
            }
            QueryPassengerResponse queryPassenger = user.queryPassenger(response);
            Person invalid = new Person();
            if (!queryPassenger.contains(persons, invalid)) {
                LOGGER.warn("对不起！{} 不允许购票，请核对身份信息是否正确，并确认已添加到“常用联系人”中。", invalid);
                System.exit(0);
            }
            
            // 查票
            final QueryTicketRequest condition = new QueryTicketRequest();
            List<Train> allows = null;
            do {
                condition.setTrainDate(trainDate);
                condition.setFromStation(StationUtils.find(trainFrom));
                condition.setToStation(StationUtils.find(trainTo));
                condition.setIncludeTrain(Lists.newArrayList(StringUtils.split(includeSource, ",")));
                condition.setExcludeTrain(Lists.newArrayList(StringUtils.split(excludeSource, ",")));
                condition.setSeats(seats);
                condition.setTicketQuantity(persons.size());
                QueryTicketResponse result = ticket.query(response, condition);
                allows = result.getAllows();
                if (CollectionUtils.isEmpty(allows)) {
                    LOGGER.info("暂时没有符合预订条件的车次。");
                    Thread.sleep(queryTicketSleepTime);
                }
            } while (CollectionUtils.isEmpty(allows));
            
            for (final Train train : allows) {
                SubmitOrderRequest submitOrderRequest = new SubmitOrderRequest(trainDate, trainDate, condition.getPurposeCodes(), train);
                Response submitResponse = order.submit(response, submitOrderRequest);
                if (submitResponse.isContinue()) {
                    List<Seat> canBuySeats = train.getCanBuySeats(); // 允许购买的席别
                    Seat seat = canBuySeats.get(0);
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("乘车人为 {}，席别为 [{}]。", persons, Seat.getDescription(seat));
                    }
                    
                    Token token = order.getToken(response);
                    
                    // 检查订单完整性
                    String seatTypes = train.getQueryLeftNewDTO().getSeat_types();
                    String passengerTicketStr = PersonUtils.toPassengerTicketStr(persons, seat, seatTypes);
                    
                    CheckOrderInfoRequest checkOrderInfoRequest = new CheckOrderInfoRequest();
                    checkOrderInfoRequest.setRepeatSubmitToken(token.getToken());
                    checkOrderInfoRequest.setPassengerTicketStr(passengerTicketStr);
                    CheckOrderInfoResponse checkOrderInfo = new CheckOrderInfoResponse();
                    byte[] code = null;
                    do {
                        File orderPassCode = passCode.getOrderPassCode(response);
                        if (recognition) {
                            code = antiUtils.recognition(4, orderPassCode.getPath());
                        }
                        else {
                            System.out.println("请输入验证码(" + orderPassCode.getPath() + ")并回车确认：");
                            Scanner scanner = new Scanner(System.in);
                            code = scanner.next().getBytes();
                        }
                        checkOrderInfoRequest.setRandCode(new String(code));
                        
                        checkOrderInfo = order.checkOrderInfo(response, checkOrderInfoRequest);
                        if (checkOrderInfo.isContinue()) {
                            break;
                        }
                        else {
                            LOGGER.warn(checkOrderInfo.getMessage());
                            if (StringUtils.indexOf(checkOrderInfo.getMessage(), "对不起，由于您取消次数过多，今日将不能继续受理您的订票请求") != -1) {
                                System.exit(0);
                            }
                        }
                    } while (!checkOrderInfo.isContinue());
                    
                    // 提交订单
                    ConfirmSingleForQueueRequest queueRequest = new ConfirmSingleForQueueRequest();
                    queueRequest.setKeyCheckIsChange(token.getOrderKey());
                    queueRequest.setLeftTicketStr(train.getQueryLeftNewDTO().getYp_info());
                    queueRequest.setPassengerTicketStr(passengerTicketStr);
                    queueRequest.setRandCode(new String(code));
                    queueRequest.setRepeatSubmitToken(token.getToken());
                    queueRequest.setTrainLocation(train.getQueryLeftNewDTO().getLocation_code());
                    
                    ConfirmSingleForQueueResponse confirmResponse = order.confirm(response, queueRequest);
                    if (confirmResponse.isContinue()) {
                        
                        OrderWaitTimeResponse waitTimeResponse = new OrderWaitTimeResponse();
                        do {
                            waitTimeResponse = order.queryOrderWaitTime(response, token);
                            if (!waitTimeResponse.isContinue()) {
                                LOGGER.info("订单已经提交，正在等待结果，大概还需要 {} 秒", waitTimeResponse.getData().getWaitTime());
                                Thread.sleep(1000);
                            }
                        } while (!waitTimeResponse.isContinue());
                        
                        if (StringUtils.isBlank(waitTimeResponse.getData().getOrderId())) {
                            LOGGER.warn("对不起，订单处理失败，原因暂时不明。");
                            System.exit(0);
                        }
                        
                        NoCompleteOrderResponse noComplete = new NoCompleteOrderResponse();
                        do {
                            noComplete = order.queryNoComplete(response);
                            if (noComplete.isContinue()) {
                                Report report = new Report();
                                report.setUsername(user.getUsername());
                                report.setOrders(noComplete.getData().getOrderDBList());
                                ticketReport.write(report);
                                
                                LOGGER.info("恭喜车票预订成功，请尽快登录12306客运服务后台进行支付。");
                                System.out.println();
                                System.out.println(noComplete.toString());
                                return;
                            }
                        } while (!noComplete.isContinue());
                    }
                    else {
                        LOGGER.warn(confirmResponse.toString());
                    }
                }
                else {
                    if (StringUtils.startsWith(submitResponse.getMessage(), "您还有未处理的订单")) {
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn("您还有未处理的订单，请登录12306客运服务后台->未完成订单 进行处理!");
                        }
                        return;
                    }
                    else {
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(submitResponse.getMessage());
                        }
                    }
                }
            }
        }
        catch (Exception e) {
        }
    }

    private Ticket4jHttpResponse initTicket4jHttpResponse(Initialize initialize, User user) {
        Ticket4jHttpResponse response = null;
        CheckUserResponse cur = user.check(cookieCache);
        if (!cur.isContinue()) {
            response = initialize.init();
            response.setSignIn(false);
            cookieCache.write(response.getHeaders(), user.getUsername());
        }
        else {
            response = cur.getTicket4jHttpResponse();
            response.setSignIn(true);
        }
        return response;
    }

    public void setPassengers(String passengers) {
        Preconditions.checkArgument(StringUtils.isNotBlank(passengers), "没有乘车人信息!");
        this.passengers = passengers;
    }

    public void setSeatSource(String seatSource) {
        this.seatSource = seatSource;
    }

    public void setTrainDate(String trainDate) {
        Preconditions.checkArgument(StringUtils.isNotBlank(trainDate), "没有设置乘车日期!");
        this.trainDate = CharsetUtils.convert(trainDate);
    }

    public void setTrainFrom(String trainFrom) {
        Preconditions.checkArgument(StringUtils.isNotBlank(trainFrom), "没有设置出发站!");
        this.trainFrom = CharsetUtils.convert(trainFrom);
    }

    public void setTrainTo(String trainTo) {
        Preconditions.checkArgument(StringUtils.isNotBlank(trainTo), "没有设置到达站!");
        this.trainTo = CharsetUtils.convert(trainTo);
    }

    public void setIncludeSource(String includeSource) {
        this.includeSource = includeSource;
    }

    public void setExcludeSource(String excludeSource) {
        this.excludeSource = excludeSource;
    }

    public void setQueryTicketSleepTime(int queryTicketSleepTime) {
        Preconditions.checkArgument(queryTicketSleepTime >= 1000, "查询车次间隔时间不得低于1秒（1000毫秒）");
        this.queryTicketSleepTime = queryTicketSleepTime == 12344321 ? 100 : queryTicketSleepTime;
    }

    public void setRecognition(boolean recognition) {
        this.recognition = recognition;
    }
}
