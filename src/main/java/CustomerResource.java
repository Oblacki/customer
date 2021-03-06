import com.fasterxml.jackson.databind.ObjectMapper;

import com.kumuluz.ee.configuration.utils.ConfigurationUtil;
import com.kumuluz.ee.discovery.annotations.DiscoverService;

import javax.enterprise.context.RequestScoped;
import com.kumuluz.ee.logs.cdi.Log;
import javax.annotation.PostConstruct;
import javax.ws.rs.*;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.ws.rs.client.Client;
import org.eclipse.microprofile.metrics.annotation.Metered;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Timeout;

@RequestScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Path("customers")
@Log
public class CustomerResource {

    private Client httpClient;
    private ObjectMapper objectMapper;

    @Inject
    @DiscoverService(value = "apartments", version = "1.0.x", environment = "dev")
    private Optional<String> baseUrl;

    @PostConstruct
    private void init() {
        httpClient = ClientBuilder.newClient();
        objectMapper = new ObjectMapper();
    }

    @Inject
    private RestProperties restProperties;

    @Inject
    CustomerResource customerResource;

    @GET
    @Metered
    public Response getAllCustomers() {
        List<Customer> customers = Database.getCustomers();
        return Response.ok(customers).build();
    }

    @GET
    @Path("{customerId}")
    public Response getCustomer(@PathParam("customerId") String customerId) {
        Customer customer = Database.getCustomer(customerId);

        List<Apartment> apartments = null;

        Optional<Boolean> apartmentServiceEnabled = ConfigurationUtil.getInstance().getBoolean("rest-properties.external-services.apartment-service.enabled");
        System.out.println("----------------------" +apartmentServiceEnabled.get()+ "-------------------------");


        System.out.println("---------------" + restProperties.isApartmentServiceEnabled() + "---------------------");
        if(restProperties.isApartmentServiceEnabled())
            apartments = customerResource.getApartments(customerId);

        customer.setApartments(apartments);

        return customer != null
                ? Response.ok(customer).build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    @POST
    public Response addNewCustomer(Customer customer) {
        Database.addCustomer(customer);
        return Response.noContent().build();
    }

    @DELETE
    @Path("{customerId}")
    public Response deleteCustomer(@PathParam("customerId") String customerId) {
        Database.deleteCustomer(customerId);
        return Response.noContent().build();
    }

    @CircuitBreaker(requestVolumeThreshold = 2)
    @Fallback(fallbackMethod = "getApartmentsFallback")
    @Timeout//(value = 2, unit = ChronoUnit.SECONDS)
    public List<Apartment> getApartments(String customerId){

        if(baseUrl.isPresent()) {
            try {
                System.out.println(baseUrl.get() + "/v1/apartments/customer/" + customerId);
                return httpClient.target(baseUrl.get() + "/v1/apartments/customer/" + customerId).request()
                        .get(new GenericType<List<Apartment>>() {});


            } catch (Exception e) {
                String msg = e.getClass().getName() + " occured: " + e.getMessage();
                throw new InternalServerErrorException(msg);
            }
        }
        else
            return new ArrayList<>();
    }

    public List<Apartment> getApartmentsFallback(String customerId) {

        List<Apartment> apartments = new ArrayList<>();

        Apartment ap = new Apartment();

        ap.setId("N/A");
        ap.setNumOfBeds(0);
        ap.setCustomerId("N/A");

        List<String> guestIds = new ArrayList<>();
        guestIds.add("N/A");
        ap.setGuestIds(guestIds);

        ap.setPricePerNight(-1.0);

        apartments.add(ap);

        return apartments;

    }

    //external call - from superhost
    @GET
    @Path("/superhost")
    public Response getApartmentByCustomer(@QueryParam("superhost") boolean superhost) {
        System.out.println("customers/superhost/...");
        List<Customer> customers = Database.getCustomersBySuperhost(superhost);
        return customers != null
                ? Response.ok(customers).build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    private List<Apartment> getObjects(String json) throws IOException {
        return json == null ? new ArrayList<>() : objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Apartment.class));
    }
}
