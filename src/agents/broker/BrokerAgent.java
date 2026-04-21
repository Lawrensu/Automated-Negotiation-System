package agents.broker;
import jade.core.Agent;

import agents.broker.gui.CarBrokerGui;
import jade.lang.acl.ACLMessage;

import java.util.*;


import jade.core.behaviours.*;
import jade.lang.acl.MessageTemplate;

public class BrokerAgent extends Agent{
  private CarBrokerGui myGui;
  private Hashtable<String, Integer> catalogue;


  public Map<String, Integer> getCatalogue() {
    return this.catalogue; 
  }

  protected void setup() {
    catalogue = new Hashtable<>();
    
    myGui = new CarBrokerGui(this);
    myGui.display();

    addBehaviour(new CatalogueUpdateReceiver());
  }

  

  protected void takeDown() {
    System.out.println("Bye world!");
  }


  private class CatalogueUpdateReceiver extends CyclicBehaviour {

      private final MessageTemplate mt = MessageTemplate.and(
              MessageTemplate.MatchConversationId("new-deal"),
              MessageTemplate.MatchPerformative(ACLMessage.INFORM)
      );

        @Override
        public void action() {
            ACLMessage msg = myAgent.receive(mt);
            if (msg == null) { block(); return; }

            String sellerName = msg.getSender().getLocalName();
            String[] lines    = msg.getContent().split("\n");

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                

                if (line.startsWith("ADD ")) {
                  String[] parts = line.substring(4).split("::", 2);
                  if (parts.length == 2) {
                      String key = sellerName + "::" + parts[0];
                      int price  = Integer.parseInt(parts[1].trim());
                      catalogue.put(key, price);
                      myGui.addOffer(sellerName, parts[0], price);
                  }

                } else if (line.startsWith("REMOVE ")) {
                    String carModel = line.substring(7).trim();
                    String key      = sellerName + "::" + carModel;
                    catalogue.remove(key);
                    myGui.removeOffer(sellerName, carModel);
                }
            }

            System.out.println("[BrokerAgent] Catalogue updated from "
                    + sellerName + ". Total entries: " + catalogue.size());
        }
    }
}