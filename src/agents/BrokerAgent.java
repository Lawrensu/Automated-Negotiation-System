package agents;
import jade.core.Agent;

public class BrokerAgent extends Agent{
  protected void setup() {
    System.out.println("Hello world!");
  }

  protected void takeDown() {
    System.out.println("Bye world!");
  }
}