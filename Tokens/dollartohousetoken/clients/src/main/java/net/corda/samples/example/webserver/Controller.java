package net.corda.samples.example.webserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.corda.client.jackson.JacksonSupport;
import net.corda.core.contracts.*;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.node.NodeInfo;
import net.corda.core.transactions.SignedTransaction;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import net.corda.samples.dollartohousetoken.flows.FiatCurrencyIssueFlow;
import net.corda.samples.dollartohousetoken.flows.FiatCurrencyMoveFlow;
import net.corda.samples.dollartohousetoken.flows.FiatCurrencyQuery;

/**
 * Define your API endpoints here.
 */
@RestController
@RequestMapping("/") // The paths for HTTP requests are relative to this base path.
public class Controller {
    private static final Logger logger = LoggerFactory.getLogger(RestController.class);
    private final CordaRPCOps proxy;
    private final CordaX500Name me;

    public Controller(NodeRPCConnection rpc) {
        this.proxy = rpc.proxy;
        this.me = proxy.nodeInfo().getLegalIdentities().get(0).getName();

    }

    /** Helpers for filtering the network map cache. */
    public String toDisplayString(X500Name name){
        return BCStyle.INSTANCE.toString(name);
    }

    private boolean isNotary(NodeInfo nodeInfo) {
        return !proxy.notaryIdentities()
                .stream().filter(el -> nodeInfo.isLegalIdentity(el))
                .collect(Collectors.toList()).isEmpty();
    }

    private boolean isMe(NodeInfo nodeInfo){
        return nodeInfo.getLegalIdentities().get(0).getName().equals(me);
    }

    private boolean isNetworkMap(NodeInfo nodeInfo){
        return nodeInfo.getLegalIdentities().get(0).getName().getOrganisation().equals("Network Map Service");
    }

    @Configuration
    class Plugin {
        @Bean
        public ObjectMapper registerModule() {
            return JacksonSupport.createNonRpcMapper();
        }
    }

    @GetMapping(value = "/status", produces = TEXT_PLAIN_VALUE)
    private String status() {
        return "200";
    }

    @GetMapping(value = "/servertime", produces = TEXT_PLAIN_VALUE)
    private String serverTime() {
        return (LocalDateTime.ofInstant(proxy.currentNodeTime(), ZoneId.of("UTC"))).toString();
    }

    @GetMapping(value = "/addresses", produces = TEXT_PLAIN_VALUE)
    private String addresses() {
        return proxy.nodeInfo().getAddresses().toString();
    }

    @GetMapping(value = "/identities", produces = TEXT_PLAIN_VALUE)
    private String identities() {
        return proxy.nodeInfo().getLegalIdentities().toString();
    }

    @GetMapping(value = "/platformversion", produces = TEXT_PLAIN_VALUE)
    private String platformVersion() {
        return Integer.toString(proxy.nodeInfo().getPlatformVersion());
    }

    @GetMapping(value = "/peers", produces = APPLICATION_JSON_VALUE)
    public HashMap<String, List<String>> getPeers() {
        HashMap<String, List<String>> myMap = new HashMap<>();

        // Find all nodes that are not notaries, ourself, or the network map.
        Stream<NodeInfo> filteredNodes = proxy.networkMapSnapshot().stream()
                .filter(el -> !isNotary(el) && !isMe(el) && !isNetworkMap(el));
        // Get their names as strings
        List<String> nodeNames = filteredNodes.map(el -> el.getLegalIdentities().get(0).getName().toString())
                .collect(Collectors.toList());

        myMap.put("peers", nodeNames);
        return myMap;
    }

    @GetMapping(value = "/notaries", produces = TEXT_PLAIN_VALUE)
    private String notaries() {
        return proxy.notaryIdentities().toString();
    }

    @GetMapping(value = "/flows", produces = TEXT_PLAIN_VALUE)
    private String flows() {
        return proxy.registeredFlows().toString();
    }

    @GetMapping(value = "/states", produces = TEXT_PLAIN_VALUE)
    private String states() {
        return proxy.vaultQuery(ContractState.class).getStates().toString();
    }

    @GetMapping(value = "/me",produces = APPLICATION_JSON_VALUE)
    private HashMap<String, String> whoami(){
        HashMap<String, String> myMap = new HashMap<>();
        myMap.put("me", me.toString());
        return myMap;
    }

    @PostMapping(value = "create-token", produces = TEXT_PLAIN_VALUE, headers = "Content-Type=application/x-www-form-urlencoded")
    public ResponseEntity<String> issueIOU(HttpServletRequest request) throws IllegalArgumentException {

        Long amount = Long.valueOf(request.getParameter("amount"));
        String party = request.getParameter("recipient");
        String currency = request.getParameter("currency");
        // Get party objects for recipient and the currency.
        System.out.println(amount);
        System.out.println(currency);
        System.out.println(party);
        CordaX500Name partyX500Name = CordaX500Name.parse(party);
        Party otherParty = proxy.wellKnownPartyFromX500Name(partyX500Name);

        // Create a new token state using the parameters given.
        try {
            // Start the IssueFlow. We block and waits for the flow to return.
            SignedTransaction result = proxy.startTrackedFlowDynamic(FiatCurrencyIssueFlow.class, currency, amount, otherParty)
                    .getReturnValue().get();
            // Return the response.
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body("Transaction id " + result.getId() + " committed to ledger.\n "
                            + result.getTx().getOutput(0));
            // For the purposes of this demo app, we do not differentiate by exception type.
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(e.getMessage());
        }
    }

    @PostMapping(value = "burn-token", produces = TEXT_PLAIN_VALUE, headers = "Content-Type=application/x-www-form-urlencoded")
    public ResponseEntity<String> burnIOU(HttpServletRequest request) throws IllegalArgumentException {

        Long amount = Long.valueOf(request.getParameter("amount"));
        String party = request.getParameter("recipient");
        String currency = request.getParameter("currency");
        // Get party objects for recipient and the currency.
        System.out.println(amount);
        System.out.println(currency);
        System.out.println(party);
        CordaX500Name partyX500Name = CordaX500Name.parse(party);
        Party otherParty = proxy.wellKnownPartyFromX500Name(partyX500Name);

        // Create a new token state using the parameters given.
        try {
            // Start the IssueFlow. We block and waits for the flow to return.
            System.out.println("burning token");
            SignedTransaction result = proxy.startTrackedFlowDynamic(FiatCurrencyMoveFlow.class, currency, amount, otherParty)
                    .getReturnValue().get();
            // Return the response.
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body("Transaction id " + result.getId() + " committed to ledger.\n "
                            + result.getTx().getOutput(0));
            // For the purposes of this demo app, we do not differentiate by exception type.
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(e.getMessage());
        }
    }


    @PostMapping(value = "query-token", produces = TEXT_PLAIN_VALUE, headers = "Content-Type=application/x-www-form-urlencoded")
    public ResponseEntity<String> queryIOU(HttpServletRequest request) throws IllegalArgumentException {

        //String party = request.getParameter("recipient");
        //String currency = request.getParameter("currency");
        // just hardcode the party and currency for now
        String party = "O=PartyB,L=New York,C=US";
        String currency = "USD";

        // Get party objects for recipient and the currency.
        System.out.println(currency);
        System.out.println(party);
        CordaX500Name partyX500Name = CordaX500Name.parse(party);
        Party otherParty = proxy.wellKnownPartyFromX500Name(partyX500Name);

        // Create a new token state using the parameters given.
        try {
            // Start the IssueFlow. We block and waits for the flow to return.
            String result = proxy.startTrackedFlowDynamic(FiatCurrencyQuery.class, currency, otherParty)
                    .getReturnValue().get();
            // Return the response.
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body("Transaction: " + result + "\n ");
            // For the purposes of this demo app, we do not differentiate by exception type.
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(e.getMessage());
        }
    }

}