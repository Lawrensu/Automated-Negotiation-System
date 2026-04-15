package agents;
import jade.core.Agent;
import jade.core.behaviours.*;
import gui.CarDealerGui;
import java.util.*;

public class DealerAgent extends Agent{

  private Hashtable<String, Integer> catalogue = new Hashtable<>();

  private CarDealerGui myGui;

  protected void setup() {
    myGui = new CarDealerGui(this);
    myGui.display();
  }

  protected void takeDown() {
    System.out.println("Bye world!");
    myGui.dispose();
  }

  public void updateCatalogue(final String carModel, final int price) {
    addBehaviour(new OneShotBehaviour() {
      public void action() {
        catalogue.put(carModel, price);
        System.out.println(carModel+" inserted into catalogue. Price = "+price);
      }
    });
  }
}
