package ar.uba.dc.formalex.util;

import ar.uba.dc.formalex.fl.FLInput;
import ar.uba.dc.formalex.fl.bgtheory.*;
import ar.uba.dc.formalex.fl.regulation.formula.FLFormula;

import org.apache.log4j.Logger;

import java.util.*;

/**
 * User: P_BENEDETTI
 * Date: 03/12/13
 * Time: 16:21
 */
public class LaAplanadora {
	private static final Logger logger = Logger.getLogger(LaAplanadora.class);

	private static final String PREFIJO_AGENTE = "agent_";
	private static final String SEPARADOR_ACCIONES_SYNC = "-";
	private static final String SEPARADOR_AGENTE_ACCION = ".";
	private static final String SEPARADOR_AGENTE_INTERVALO = ".";
	private static final String SEPARADOR_AGENTE_CONTADOR = ".";

	//un map con k = acci�n original, v = set de acciones nuevas
	private Map<Action, Set<Action>> accionesSyncActivo = new HashMap<Action, Set<Action>>();
	private Map<Action, Set<Action>> accionesSyncPasivo = new HashMap<Action, Set<Action>>();
	private Map<Action, Agente> accionesYAgentes = new HashMap<Action, Agente>();
	private Map<Interval, Agente> intervalosYAgentes = new HashMap<Interval, Agente>();
	private Map<Counter, Agente> contadoresYAgentes = new HashMap<Counter, Agente>();

	//por cada acci�n nueva (agente-acci�n), ac� se guarda su relaci�n con la original
	private Map<Action, Set<Action>> accionesOriginales = new HashMap<Action, Set<Action>>();
	private Map<Interval, Set<Interval>> intervalosOriginales = new HashMap<Interval, Set<Interval>>();
	private Map<Counter, Set<Counter>> contadoresOriginales = new HashMap<Counter, Set<Counter>>();

	private Set<Action> impersonalAction = new HashSet<Action>();
	private Set<Agente> agentes = null;


	public void explotarYAplanar(FLInput input) {
		Set<Action> accionesConAgentes = new HashSet<Action>();
		Set<Action> accionesAplanadasConSyncActivo = crearAgentesYAplanarAcciones(input, accionesConAgentes);

		//Aplano acciones sincronizadas
		aplanarAccionesSincronizadas(accionesConAgentes, accionesAplanadasConSyncActivo);
		input.setActions(accionesConAgentes);

		Set<Interval> nuevosInt = crearNuevosIntervalos(input.getIntervals());
		input.setIntervals(nuevosInt);

		Set<Counter> countersConAgentes = crearNuevosContadores(input.getCounters());
		input.setCounters(countersConAgentes);

		actualizarOccurrsInIntervalos(nuevosInt);
		actualizarOccurrsIn(accionesConAgentes);

		expandirClausulas(input);
		loguear(input);
	}

	//expande e instancia las cl�ulas
	private void expandirClausulas(FLInput input) {
		List<FLFormula> newRules = new ArrayList<FLFormula>();
		List<FLFormula> newPerms = new ArrayList<FLFormula>();

		//[serge] No entiendo por qué en lugar de BGUtil no se pasa directamente la info que tiene LaAplanadora. BGUtil se maneja con strings planos...
		// Como parche, paso las acciones activas y pasivas para poder instancia correctamente acciones atómicas cuando hay sincronizaciones
		BGUtil bgUtil = new BGUtil(input.getAgentesPorRol(), input.getNamesWithAgent(), accionesSyncActivo, accionesSyncPasivo);

		for(FLFormula formula : input.getRules()) {
			newRules.add(formula.instanciar(null, null, bgUtil, false));
		}
		input.setRules(newRules);

		for(FLFormula formula : input.getRegulation().getPermissions()) {
			newPerms.add(formula.instanciar(null, null, bgUtil, false));
		}
		input.setPermissions(newPerms);
	}


	private void loguear(FLInput input) {
		int contInterv = 0;
		for (Set<Interval> si : intervalosOriginales.values()) {
			contInterv += si.size();
		}
		int contCounters = 0;
		for (Set<Counter> c : contadoresOriginales.values()) {
			contCounters += c.size();
		}

		logger.info("# agentes: " + agentes.size());
		logger.info("# acciones: " + accionesYAgentes.size());
		logger.info("# acciones impersonales: " + impersonalAction.size());
		logger.info("# contadores: " + contCounters);
		logger.info("# intervalos: " + contInterv);
		logger.info("# fórmulas: " + input.getRules().size());
		logger.info("# permisos: " + input.getPermissions().size());
		logger.info("");

		logger.info("Agentes creados: ");

		for (Agente agente : agentes) {
			logger.info(agente.getName() + ": " + agente.getRolesCSV());
		}
		logger.info("");
		logger.info("Acciones con agentes:");
		for (Action a : accionesYAgentes.keySet()) {
			a.logFL();
		}
		logger.info("");
		if (impersonalAction.size() > 0){
			logger.info("Acciones impersonales:");
			for(Action a : impersonalAction) {
				a.logFL();
			}
			logger.info("");
		}

		if (intervalosOriginales.values().size() > 0){
			logger.info("");
			logger.info("Intervalos:");
			for (Set<Interval> si : intervalosOriginales.values()) {
				for (Interval i : si) {
					i.logFL();
				}
			}
		}

		if (contadoresOriginales.values().size() > 0){
			logger.info("");
			logger.info("Contadores:");
			for (Set<Counter> si : contadoresOriginales.values()) {
				for (Counter i : si) {
					i.logFL();
				}
			}
		}
		logger.info("");
		logger.info("");

		//F�rmulas
		logger.info("Fórmulas expandidas:");

		for(FLFormula f : input.getRules()) {
			logger.info(f.toString());
		}
		logger.info("");
		for(FLFormula f : input.getPermissions()) {
			logger.info(f.toString());
		}
		logger.info("");
	}

	/*
	 * si el inter > es Global, no hay que hacer nada(ej: interv occursIn intGlobal)
	 * si el inter < es Global, => no tienen sentido que el mayor se local (ej: intGlogal occursIn intLocal)
	 * si ambos con locales, va con agentes
	 * (todo: puede no existir un agente para ambas, hay que ver si tiene sentido. hoy si pasa esto no genera el intervalo)
	 * */
	private void actualizarOccurrsInIntervalos(Set<Interval> intervalos) {
		Set<Interval> intervalosABorrar  = new HashSet<Interval>();

		for (Interval inter : intervalos) {
			if (inter.hasOccursIn()){
				Interval occursIn = inter.getOccursIn();
				//si occursIn es global =>  no cambia de nombre (no se hace nada )
				if (occursIn.isLocal()){
					if (!inter.isLocal())
						throw new RuntimeException("intervaloGlobal con occurs in intervaloLocal no soportado.");
					Agente ag = getAgente(inter);
					Interval nuevoOccurrsIn = getIntervaloDeAgente(occursIn, ag);
					if(nuevoOccurrsIn == null){
						System.out.println("VER:");
						System.out.println("ag = " + ag);
						System.out.println("inter = " + inter);
						System.out.println("occursIn = " + occursIn);
						System.out.println("Esto pasa cuando hay un agente del intervalo  que no puede realizar alguna " +
								"de las acciones iniciales y/o de las finales");
						intervalosABorrar.add(inter);
					}
					else
						inter.setOccursIn(nuevoOccurrsIn);
				}
			}
		}

		//se borran los del caso logueado arriba
		for (Interval interval : intervalosABorrar) {
			intervalos.remove(interval);
			borrarIntervalo(interval);
		}
	}

	private void borrarIntervalo(Interval interval) {
		intervalosYAgentes.remove(interval);
		for (Set<Interval> set : intervalosOriginales.values()) {
			set.remove(interval);//deber�a estar en un solo set
		}
	}

	private Agente getAgente(Interval inter) {
		return intervalosYAgentes.get(inter);
	}


	private void actualizarOccurrsIn(Set<Action> accionesConAgentes) {
		for (Action accion : accionesConAgentes) {
			if (accion.hasOccursIn()){
				Interval occursIn = accion.getOccursIn();
				Set<Interval> intervals = intervalosOriginales.get(occursIn);
				Interval newInt;
				if(!occursIn.isLocal()){
					if (intervals.size() != 1){
						//no deber�a pasar
						throw new RuntimeException("puede fallar. revisar");
					}
					newInt = intervals.iterator().next();
				}else{// es local
					Agente agente = accionesYAgentes.get(accion); //todo: ver impersonal action
					newInt = getIntervaloDeAgente(intervals,  agente);
				}
				accion.setOccursIn(newInt);
			}
		}
	}


	private Interval getIntervaloDeAgente(Interval inter, Agente ag) {
		Set<Interval> intervals = intervalosOriginales.get(inter);
		return getIntervaloDeAgente(intervals, ag);
	}


	private Interval getIntervaloDeAgente(Set<Interval> intervals, Agente agente) {
		for (Interval interval : intervals) {
			if (interval.getName().startsWith(agente.getName() + SEPARADOR_AGENTE_INTERVALO))
				return interval;
		}
		return null;
	}

	private Set<Interval> crearNuevosIntervalos(Set<Interval> intervals) {
		Set<Interval> res = new HashSet<Interval>();

		if (intervals != null){
			for (Interval interval : intervals) {
				if (interval.isLocal() ){

					//se crea un intervalo por cada agente que realice por lo menos una de las acciones
					//iniciales y por lo menos una de las finales (o haya impersonal action, StartActive o endActive)
					for (Agente ag : agentes) {
						if (realizaAcciones(ag, interval)){
							Interval intL = crearIntervaloLocal(interval, ag);
							res.add(intL);
							agregar(intervalosOriginales, interval,  intL);
							intervalosYAgentes.put(intL,  ag);
						}
					}
				}
				else{//es global-> al hacer la explotaci�n-aplanamiento queda un solo intervalo con varias acciones iniciales y finales
					Interval intG = crearIntervaloGlobal(interval);

					res.add(intG);
					agregar(intervalosOriginales, interval,  intG);
				}
			}
		}
		return res;
	}

	private Set<Counter> crearNuevosContadores(Set<Counter> counters) {
		Set<Counter> res = new HashSet<Counter>();

		if (counters != null){
			for (Counter contOriginal : counters) {
				if (contOriginal.isLocal() ){

					//se crea un contador por cada agente que realice por lo menos una de las acciones que modifican al contador
					for (Agente ag : agentes) {
						if (realizaAcciones(ag, contOriginal)){
							Counter contL = crearContadorLocal(contOriginal, ag);
							res.add(contL);
							agregar(contadoresOriginales, contOriginal,  contL);
							contadoresYAgentes.put(contL,  ag);
						}
					}
				}
				else{//es global-> al hacer la explotaci�n-aplanamiento queda un solo contador donde las acciones que
					//lo modifican se ampl�an con las nuevas acciones con agentes
					Counter contG = crearContadorGlobal(contOriginal);

					res.add(contG);
					agregar(contadoresOriginales, contOriginal,  contG);
				}
			}
		}

		return res;
	}

	private Counter crearContadorLocal(Counter counterOriginal, Agente agente) {
		Counter res = new Counter(agente.getName() + SEPARADOR_AGENTE_CONTADOR + counterOriginal.getName());
		res.setInitValue(counterOriginal.getInitValue());
		res.setMinValue(counterOriginal.getMinValue());
		res.setMaxValue(counterOriginal.getMaxValue());                
		res.setMinImpedesActions((Boolean)counterOriginal.isMinImpedesActions());
		res.setMaxImpedesActions((Boolean)counterOriginal.isMaxImpedesActions());

        //reemplazo c/u de las acciones x las nuevas con agentes
        Map<Action, Integer> incrActions = counterOriginal.getIncreaseActions();
        for (Action accOriginal : incrActions.keySet()) {
            String pt = counterOriginal.getCondition(accOriginal);
            Integer value = incrActions.get(accOriginal);
            if (accOriginal.isImpersonal()){
                res.addIncreaseAction(accOriginal, value, pt);
            }
            else {
                Action accionConAgente = getAccionConAgente(accOriginal, agente);
                if (accionConAgente != null){

                    if (!accOriginal.hasActiveSync() && !accOriginal.hasPasiveSync())
                        res.addIncreaseAction(accionConAgente, value, pt);
                    else {
                        Set<Action> accionesSync = getAccionesSync(accionConAgente, true, true);
                        for (Action actionSync : accionesSync) {
                            res.addIncreaseAction(actionSync, value, pt);
                        }
                    }
                }
            }
		}

        Map<Action, Integer> setValueActions = counterOriginal.getSetValueActions();
        for (Action accOriginal : setValueActions.keySet()) {
            String pt = counterOriginal.getCondition(accOriginal);
            Integer value = setValueActions.get(accOriginal);
            if (accOriginal.isImpersonal()){
                res.addSetValueAction(accOriginal, value, pt);
            }
            else {
                Action accionConAgente = getAccionConAgente(accOriginal, agente);
                if (accionConAgente != null){
                    if (!accOriginal.hasActiveSync() && !accOriginal.hasPasiveSync())
                        res.addSetValueAction(accionConAgente, value, pt);
                    else {
                        Set<Action> accionesSync = getAccionesSync(accionConAgente, true, true);
                        for (Action actionSync : accionesSync) {
                            res.addSetValueAction(actionSync, value, pt);
                        }
                    }
                }
            }
        }
		return res;
	}

	private Interval crearIntervaloLocal(Interval interval, Agente agente) {
		Interval res = interval.clonar(agente.getName() + SEPARADOR_AGENTE_INTERVALO + interval.getName());

		//reemplazo c/u de las accIni x las nuevas con agentes (o si hay una impersonal action la dejo tal cual)
		Set<Action> newST = getAccionesParaIntervalo(agente, interval.getStartTriggers());
		res.setStartTriggers(newST);

		//reemplazo c/u de las accFin x las nuevas con agentes (o si hay una impersonal action la dejo tal cual)
		Set<Action> newET = getAccionesParaIntervalo(agente, interval.getEndTriggers());
		res.setEndTriggers(newET);

		return res;
	}

	private Set<Action> getAccionesParaIntervalo(Agente agente, Set<Action> acciones) {
		Set<Action> newSet = new HashSet<Action>(); //uso Set para evitar los repetidos
		for (Action action : acciones) {
			if (action.isImpersonal()){
				newSet.add(action);
			}
			else{
                Action accionConAgente = getAccionConAgente(action, agente);
                if (accionConAgente != null){  //es null si el agente no realiza la acción.
                    if (!action.hasActiveSync() && !action.hasPasiveSync())
                        newSet.add(accionConAgente);
                    else {
                        Set<Action> accionesSync = getAccionesSync(accionConAgente, true, true);
                        newSet.addAll(accionesSync);
                    }
                }
			}
		}
		return newSet;
	}


    //devuelve true si el agente realiza por lo menos una de las acciones iniciales (o el intervalo
    // arranca activo -por isStartActive- o hay 'impersonal action') y por lo menos una de las finales (o el intervalo
    // es isEndActive o hay 'impersonal action')
    private static boolean realizaAcciones(Agente ag, Interval interval) {
        //parche por el agente sin rol (que no recuerdo para qué se usa y tal vez se podría bolar)
        if (ag.getName().equals("agent_without_role"))
            return false;

        Set<Action> accionesS = interval.getStartTriggers();
        Set<Action> accionesE = interval.getEndTriggers();
        return (ag.realizaAlgunaAccion(accionesS) || interval.isStartActive() || hayImpersonalAction(accionesS) )&&
                (ag.realizaAlgunaAccion(accionesE) || interval.isEndActive() || hayImpersonalAction(accionesE) );
    }

    //devuelve true si entre las acciones recibidas hay por lo menos una impersonal action
    private static boolean hayImpersonalAction(Set<Action> actions) {
        for(Action action : actions) {
            if (action.isImpersonal())
                return true;
        }
        return false;
    }

	//devuelve true si el agente realiza por lo menos una de las acciones que modifican al contador
	private static boolean realizaAcciones(Agente ag, Counter counter) {
		Set<Action> acciones = counter.getAllActions();
		return ag.realizaAlgunaAccion(acciones);
	}

    private Counter crearContadorGlobal(Counter counterOriginal) {
        Counter res = new Counter(counterOriginal.getName());
        res.setInitValue(counterOriginal.getInitValue());

        //reemplazo c/u de las acciones x las nuevas con agentes
        Map<Action, Integer> incrActions = counterOriginal.getIncreaseActions();
        for (Action accOriginal : incrActions.keySet()) {
            String pt = counterOriginal.getCondition(accOriginal);
            if (accOriginal.isImpersonal()){
                res.addIncreaseAction(accOriginal, incrActions.get(accOriginal), pt);
            }
            else {
                Set<Action> accionesConAgentes = getAccionesConAgente(accOriginal);
                for (Action accionConAgente : accionesConAgentes) {
                    res.addIncreaseAction(accionConAgente, incrActions.get(accOriginal), pt);
                }
            }
        }

        Map<Action, Integer> setValueActions = counterOriginal.getSetValueActions();
        for (Action accOriginal : setValueActions.keySet()) {
            String pt = counterOriginal.getCondition(accOriginal);
            if (accOriginal.isImpersonal()){
                res.addSetValueAction(accOriginal, setValueActions.get(accOriginal), pt);
            }
            else {
                Set<Action> accionesConAgentes = getAccionesConAgente(accOriginal);
                for (Action accionConAgente : accionesConAgentes) {
                    res.addSetValueAction(accionConAgente, setValueActions.get(accOriginal), pt);
                }
            }
        }
        
		return res;
	}

	private Interval crearIntervaloGlobal(Interval interval) {
		Interval res = interval.clonar();

		//reemplazo c/u de las accIni x las nuevas con agentes
		Set<Action> newST = getAccionesParaIntervaloG(interval.getStartTriggers());
		res.setStartTriggers(newST);

		//reemplazo c/u de las accFin x las nuevas con agentes
		Set<Action> newET = getAccionesParaIntervaloG(interval.getEndTriggers());
		res.setEndTriggers(newET);

		return res;
	}

	private Set<Action> getAccionesParaIntervaloG(Set<Action> acciones) {
		Set<Action> newSet = new HashSet<Action>();//con el set evito repetidos por active/pasive
		for (Action ai : acciones) {
			if (ai.isImpersonal())
				newSet.add(ai);
			else{
				Set<Action> accionesConAgentes = getAccionesConAgente(ai);
				if (accionesConAgentes != null && accionesConAgentes.size() > 0){
					if (!ai.hasActiveSync() && !ai.hasPasiveSync())
						newSet.addAll(accionesConAgentes);
					else {
						Set<Action> accionesSync = getAccionesSync(accionesConAgentes, true, true);
						newSet.addAll(accionesSync);
					}
				}else{
					newSet.add(ai);//si no tiene roles -> no tiene agentes. Agrego la original
					//ahora con el agente sin roles esto no debe pasar
					throw new RuntimeException("VER");
				}
			}
		}
		return newSet;
	}

	//Devuelve las acciones con Sync Activo que a�n no fueron aplanadas. Las acciones devueltas incluyen los agentes
	private Set<Action> crearAgentesYAplanarAcciones(FLInput datosAutomata, Set<Action> accionesConAgentes) {
		Set<Action> res = new HashSet<Action>();

		//Primero creo los agentes
		agentes = crearAgentes(datosAutomata.getRoles());
		datosAutomata.setAgentes(agentes);

		//Por cada acci�n y por cada agente que pueda realizar la acci�n, creo una nueva acci�n con el nombre aplanado
		for (Action action : datosAutomata.getActions()) {

			if (action.isImpersonal()){
				Action aplanada = action.clonar();
				if (action.hasActiveSync())
					res.add(aplanada);
				else if (action.hasPasiveSync())
					;
				else
					accionesConAgentes.add(aplanada);
				agregar(accionesOriginales, action, aplanada);
				impersonalAction.add(aplanada);
			}
			else{
				for (Agente agente : agentes) {
					//Si no se pone el keyword 'only performable by' en una acci�n entonces la puede ejecutar cualquier agente.
					if (action.getPerformableBy().size() == 0 || agente.realizaAccion(action)){
						Action aplanada = action.clonar(agente.getName() + SEPARADOR_AGENTE_ACCION + action.getName());
						if (action.hasActiveSync())
							res.add(aplanada);
						else if (action.hasPasiveSync())
							;
						else
							accionesConAgentes.add(aplanada);
						agregar(accionesOriginales, action, aplanada);
						accionesYAgentes.put(aplanada, agente);
					}
				}
			}
		}
		return res;
	}

	private void aplanarAccionesSincronizadas(Set<Action> accionesAplanadas, Set<Action> accionesAplanadasConSyncActivo) {

		for (Action activa : accionesAplanadasConSyncActivo) {
			Set<Action> pasivas = getAccionesConAgente(activa.getSync());
			for (Action pasiva : pasivas) {
				if(activa.isAllowAutoSync() || !mismoAgente(activa,  pasiva)) {
					Action accionNueva = activa.clonar(activa.getName() + SEPARADOR_ACCIONES_SYNC + pasiva.getName());
					accionesAplanadas.add(accionNueva);
					agregar(accionesSyncActivo, activa, accionNueva);
					agregar(accionesSyncPasivo, pasiva, accionNueva);
					accionesYAgentes.put(accionNueva, accionesYAgentes.get(activa));
				}
			}
		}
	}

	//agrega en el map, en la lista que corresponda a la clave, el valor. Si la lista no existe, la crea y agrega al map
	private <T> void agregar(Map<T,Set<T>>  map, T  k, T v) {
		Set<T> set = map.get(k);
		if (set == null){
			set = new HashSet<T>();
			map.put(k, set);
		}

		set.add(v);
	}

	private boolean mismoAgente(Action actionA, Action actionB) {
		String nameA = actionA.getName();
		nameA = nameA.substring(0, nameA.indexOf(SEPARADOR_AGENTE_ACCION));
		String nameB = actionB.getName();
		nameB = nameB.substring(0, nameB.indexOf(SEPARADOR_AGENTE_ACCION));
		return nameA.equals(nameB);
	}

	//Devuelve una lista con las acciones con agentes que correspondan al par�metro
	private Set<Action> getAccionesConAgente(Action accion) {
		return accionesOriginales.get(accion);
	}

	//Devuelve la acci�n que corresponda al agente
	private Action getAccionConAgente(Action accion, Agente agente) {
		Set<Action> actions = accionesOriginales.get(accion);
		for (Action a : actions) {
			if (a.getName().startsWith(agente.getName()))
				return a;
		}
		return null;
	}

	//Devuelve un set de acciones que tengan a las accionesConAgentes como acciones pasivas y/o activasa seg�n par�metros
	private Set<Action> getAccionesSync(Set<Action> accionesConAgentes, boolean incluirActivas, boolean incluirPasivas) {
		Set<Action> res = new HashSet<Action>();

		for (Action aca : accionesConAgentes)
			res.addAll(getAccionesSync(aca, incluirActivas, incluirPasivas));

		return res;
	}

	private Set<Action> getAccionesSync(Action action, boolean incluirActivas, boolean incluirPasivas) {
		Set<Action> res = new HashSet<Action>();

		if (incluirActivas) {
			Set<Action> c = accionesSyncActivo.get(action);
			if (c != null)
				res.addAll(c);
		}
		if (incluirPasivas) {
			Set<Action> c = accionesSyncPasivo.get(action);
			if (c != null)
				res.addAll(c);
		}
		return res;
	}


	/**
	 * Crea y devuelve una lista de agentes. Cada agente cumple algunos roles. Se crear�n tantos agentes como
	 * combinaciones de roles haya (salvo disjoint)
	 * @param listaRoles
	 * @return
	 */
	private Set<Agente> crearAgentes(Set<RoleSpecification> listaRoles) {
		Set<Agente> res = new HashSet<Agente>();

		//Creo un agente sin roles
		Agente agenteSinRol = new Agente("agent_without_role");
		res.add(agenteSinRol);
		//parche x rol dummy
		Set<Role> rolesTmp = new HashSet<Role>();
		Role role = new Role("no_assigned_role");
		rolesTmp.add(role);
		agenteSinRol.setRoles(rolesTmp);

		RolesCombination rolesParaAgentes = new RolesCombination();
		for(RoleSpecification spec : listaRoles){
			combineMainRoleSpec(spec);
			//Se combina cada linea de role con las que ya se combinaron antes
			RolesCombination partialResult = combineWithPreviousResults(spec.getRolesCombination(), rolesParaAgentes);
			rolesParaAgentes = partialResult;			
		}

		//Por cada conjunto del powerSet (salvo el conjunto vacío), se forma un Agente que va a tener los roles del conjunto.		
		int cont = 1;
		for (HashSet<Role> roles : rolesParaAgentes) {
			if (roles.size() > 0){
				Agente agente = new Agente(PREFIJO_AGENTE + cont++);
				agente.setRoles(roles);
				res.add(agente);
			}
		}

		return res;
	}
	
	private RolesCombination combineWithPreviousResults(RolesCombination combination, RolesCombination partialResult){
		RolesCombination result = new RolesCombination();
		for(HashSet<Role> currenSet: combination){
			if(currenSet.size() > 0){							
				result.add(currenSet);
				for(HashSet<Role> currentPartialResult: partialResult){
					HashSet<Role> newCombination = new HashSet<Role>();
					newCombination.addAll(currenSet);
					newCombination.addAll(currentPartialResult);
					result.add(newCombination);
				}
			}
		}
		result.addAll(partialResult);
		return result;
	}

	//Devuelve true si el conjunto tiene por lo menos alg�n rol de la lista
	private boolean tieneAlgunRol(Set<Role> roles, Set<Role> rolesCover) {
		for (Role rolCover : rolesCover) {
			if(roles.contains(rolCover))
				return true;
		}
		return false;
	}

	/**
	 * @param spec
	 */
	private void combineMainRoleSpec(RoleSpecification spec){		
		if(!spec.getRoles().isEmpty()){
			Iterator<Role> roleIterator = spec.getRoles().iterator();
			while(roleIterator.hasNext()){
				Role nextRole = roleIterator.next();
				addRoleToSpecCombination(nextRole);					
			}			
		}
		combineSecondaryRoleSpec(spec);
		spec.setCombined(true);
	}

	/** 
	 * 
	 * @param roleSpecification
	 */
	private void combineSecondaryRoleSpec(RoleSpecification roleSpecification){		
		if(!roleSpecification.isDisjoint()){
			//Combino los diferentes rolesCombination de los roles evaluados sin volver a combinar los de un mismo rol.
			roleSpecification.setRolesCombination(Util.combineBetweenRoles(roleSpecification));
		}else{			
			//Agrego todos los rolesCombination de cada uno de los roles evaluados en la spec correspondiente
			for(Role role: roleSpecification.getRoles()){
				roleSpecification.getRolesCombination().addAll(role.getRoleSpecification().getRolesCombination());
			}	    		
		}
		if(roleSpecification.isCover()){
			controlCover(roleSpecification.getRolesCombination(), roleSpecification);			
		}else{
			roleSpecification.getRolesCombination().add(new HashSet<Role>());
		}
	}

	/** 
	 *   Si no fue realizada la combinación de los subroles, se hace la llamada 
	 *  al metodo que la genera
	 *   Luego, se agrega el rol superior a cada una de las combinaciones de su RoleSpecification.
	 * @param role
	 */
	private void addRoleToSpecCombination(Role role){	
		if(!role.getRoleSpecification().isCombined()){
			combineMainRoleSpec(role.getRoleSpecification());
		}
		for(HashSet<Role> aCombination: role.getRoleSpecification().getRolesCombination()){
			aCombination.add(role);
		}								
	}

	//Elimina del powerset todos los conjuntos que no tengan por lo menos uno de los roles cover
	private void controlCover(RolesCombination powerSet, RoleSpecification rolesCover) {
		HashSet<HashSet<Role>> aEliminar = new HashSet<HashSet<Role>>();
		for (HashSet<Role> roles : powerSet) {
			if (!tieneAlgunRol(roles, rolesCover.getRoles()))
				aEliminar.add(roles);
		}
		for (HashSet<Role> roles : aEliminar) {
			powerSet.remove(roles);
		}
	}		

}
