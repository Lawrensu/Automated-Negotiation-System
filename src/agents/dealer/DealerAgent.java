package agents.dealer;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.*;
import java.util.*;
import jade.lang.acl.ACLMessage;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.*;

import agents.dealer.gui.CarDealerGui;

public class DealerAgent extends Agent{

  private Hashtable<String, Integer> catalogue;
  private AID brokerAID;
  private volatile boolean pendingUpdate = false;
  private Map<String, Integer> lastSentCatalogue = new Hashtable<>();

  private CarDealerGui myGui;

  @Override
  protected void setup() {
    catalogue = new Hashtable<>();
    myGui = new CarDealerGui(this);
    myGui.display();

    Object[] args = getArguments();
    String brokerName = (args != null && args.length > 0)
      ? (String) args[0]
        : "BrokerAgent";
    brokerAID = new AID(brokerName, AID.ISLOCALNAME);

    registerWithDF();
    
    // Behaviour 1: watch for catalogue changes and push diffs to broker
    addBehaviour(new DealBroadcaster());

        // // Behaviour 2: respond to buyer CFP requests (unchanged from typical BookBuyer example)
        // addBehaviour(new OfferRequestServer());

        // // Behaviour 3: handle purchase-order messages
        // addBehaviour(new PurchaseOrderServer());

  }

  private void registerWithDF() {
    DFAgentDescription dfd = new DFAgentDescription();
    dfd.setName(getAID());
    ServiceDescription sd = new ServiceDescription();
    sd.setType("car-dealing");
    sd.setName(getLocalName() + "-car-dealer");
    dfd.addServices(sd);
    try {
        DFService.register(this, dfd);
    } catch (FIPAException fe) {
        fe.printStackTrace();
    }
    }

  protected void takeDown() {

    try {
      DFService.deregister(this);
    } catch (FIPAException fe) {
      fe.printStackTrace();
    };

    System.out.println("Bye world!");
    myGui.dispose();
  }

  public void updateCatalogue(final String carModel, final int price) {
    catalogue.put(carModel, price);
    pendingUpdate = true;
    System.out.println("[" + getLocalName() + "] Catalogue updated: "
    + carModel + " @ $" + price);
  }

  public synchronized void removeFromCatalogue(String carModel) {
    if (catalogue.remove(carModel) != null) {
        pendingUpdate = true;
        System.out.println("[" + getLocalName() + "] Removed from catalogue: " + carModel);
    }
  }

  private class DealBroadcaster extends TickerBehaviour {

        public DealBroadcaster() {
            super(DealerAgent.this, 2000); // poll every 2 s
        }

        @Override
        protected void onTick() {
            if (!pendingUpdate) return;

            synchronized (DealerAgent.this) {
                Map<String, Integer> current = new Hashtable<>(catalogue);
                Map<String, Integer> diff    = computeDiff(lastSentCatalogue, current);

                if (diff.isEmpty()) {
                    pendingUpdate = false;
                    return;
                }

                sendDiffToBroker(diff, current);

                lastSentCatalogue = new Hashtable<>(current);
                pendingUpdate = false;
            }
        }

        private Map<String, Integer> computeDiff(Map<String, Integer> previous, Map<String, Integer> current) {
          Map<String, Integer> diff = new Hashtable<>();

          // Added or price-changed items
          for (Map.Entry<String, Integer> entry : current.entrySet()) {
              String  model    = entry.getKey();
              Integer newPrice = entry.getValue();
              if (!Objects.equals(previous.get(model), newPrice)) {
                  diff.put(model, newPrice);
              }
          }

          for (String model : previous.keySet()) {
              if (!current.containsKey(model)) {
                  diff.put(model, -1);
              }
          }

          return diff;
        }

        private void sendDiffToBroker(Map<String, Integer> diff, Map<String, Integer> fullCatalogue) {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.addReceiver(brokerAID);
            msg.setConversationId("new-deal");
            msg.setReplyWith("acknowledge-deal-" + System.currentTimeMillis());

            StringBuilder sb = new StringBuilder();

            for (Map.Entry<String, Integer> entry : diff.entrySet()) {
                if (entry.getValue() == -1) {
                    sb.append("REMOVE ").append(entry.getKey()).append("\n");
                } else {
                    sb.append("ADD ")
                      .append(entry.getKey()).append("::")
                      .append(entry.getValue()).append("\n");
                }
            }

            for (Map.Entry<String, Integer> entry : fullCatalogue.entrySet()) {
                sb.append(entry.getKey()).append("::").append(entry.getValue()).append("\n");
            }

            msg.setContent(sb.toString().trim());
            send(msg);

            System.out.println("[" + getLocalName() + "] Sent deals to "
                    + brokerAID.getLocalName() + ":\n" + sb);
        }
        
    } 
  }

