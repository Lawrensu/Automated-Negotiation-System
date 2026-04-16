package agents;
import jade.core.Agent;
import gui.CarBrokerGui;
import java.util.*;

public class BrokerAgent extends Agent{
  private CarBrokerGui myGui;
  private Hashtable<String, Integer> catalogue = new Hashtable<>();


  public Map<String, Integer> getCatalogue() {
    return this.catalogue; 
}

  protected void setup() {
    myGui = new CarBrokerGui(this);
    myGui.display();
  }

  

  protected void takeDown() {
    System.out.println("Bye world!");
  }
}